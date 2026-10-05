# Typorb

**System-wide voice typing for Android.** Typorb watches for the text field you're editing, floats a
small neon orb above your keyboard, and types what you say straight into that field — cloud-fast or
completely offline.

<!-- architecture -->

## How it works

```
┌─ TyporbAccessibilityService ─────────────────────────────────────────────┐
│ onAccessibilityEvent  →  editable field focused?  +  IME on screen?      │
│        │                                                                 │
│        ├─ no ─────────────────────────────►  hide the pill immediately   │
│        ▼                                                                 │
│ OverlayController (TYPE_ACCESSIBILITY_OVERLAY window + ComposeView)        │
│        │  y = screenHeight − imeHeight − 16dp, right edge pinned 16dp     │
│        ▼                                                                 │
│ DictationCoordinator: Idle → Recording → Processing → inject → Idle       │
│        │                                                                 │
│        ├─ Cloud:  AudioRecord → WAV → Groq whisper-large-v3               │
│        │                                → llama-3.1-8b-instant (format)  │
│        └─ Local:  AudioRecord → PCM  → log-mel → ONNX Whisper tiny        │
│                                       → Kotlin regex cleanup             │
│        ▼                                                                 │
│ TextInjector: ACTION_SET_TEXT → (fallback) clipboard + ACTION_PASTE       │
│   · no field, or both refused → text is left on the clipboard to paste    │
└──────────────────────────────────────────────────────────────────────────┘
```

### 1. When does Typorb appear?

**A visible soft keyboard is the condition**, checked on every tap/focus/window-change event, by a
short 120 ms poll while one is expected but not yet on screen (several OEM keyboards never announce
that they have finished appearing), and — independently of every event — by an always-on 600 ms
watchdog that runs for as long as the service is connected and the screen is on. The watchdog exists
because event delivery is the platform's promise and some OEM builds do not keep it: without it, a
service handed no event when the keyboard appears has an orb that never shows and no symptom beyond
that.

1. **The soft keyboard is on screen** — the IME window is found by its
   `AccessibilityWindowInfo.TYPE_INPUT_METHOD` tag in `AccessibilityService.getWindows()`, falling
   back to a package match and then to the `WindowInsetsCompat.Type.ime()` bottom inset measured by a
   1×1 probe window. Its height comes from the root node's screen rectangle
   (`AccessibilityWindowInfo.getBounds` is hidden too). A floating/split keyboard counts as "no
   keyboard".
2. **A focused editable field is reported alongside it** — through
   `AccessibilityService.findFocus(FOCUS_INPUT)` first, which searches every window and therefore
   still resolves while the keyboard is up, then the active window, then a tree walk. A field counts
   as editable when it reports `isEditable`, belongs to the `EditText` family, or advertises **both**
   `ACTION_SET_TEXT` and `ACTION_SET_SELECTION` — the route that catches Compose and web fields, which
   report neither flag nor a useful class name. It is reported in Settings (`Focus lookup`) and used to
   decide whether a not-yet-visible keyboard is worth waiting for. It is deliberately **not** a
   precondition: on several OEM builds the focused field cannot be read at all, and gating on it kept
   the orb off screen while the keyboard was plainly visible.

If the keyboard drops, the pill animates out — **unless a dictation is in flight**. Recording and
processing states pin the orb on screen, because the microphone is still open and the orb is the only
button that can stop it; an app that dismisses its keyboard mid-take must not take that away. The
pinned orb disappears on its own as soon as the take finishes, or as soon as the state returns to idle.

A window the platform removes behind the app's back (an OEM's own housekeeping, a display change) is
noticed on the next watchdog tick: the stale window is dropped, the other window type leads the retry,
and the orb is added again.

### 2. The Typorb lifecycle

| State | Look | What happens |
| --- | --- | --- |
| Idle | 48dp rounded square (14dp radius), frosted glass, softly pulsing mic | Tap → start recording, `EFFECT_TICK` |
| Recording | 160dp capsule, 5 canvas bars driven by live RMS decibels | Tap → stop, waveform freezes, morph to processing |
| Processing | Rotating `Brush.sweepGradient` border, cascading ellipsis, stage label | `ACTION_SET_TEXT` (or clipboard paste), `EFFECT_CLICK` |
| Failed | Red capsule, widened to fit the reason | Auto-collapses after 2.6s |

**The orb *is* the window, so tapping it is a resize.** The window is exactly the pill's rectangle plus
14dp of shadow padding, which means every state change has to move a real
`WindowManager.LayoutParams` — and that is animated: width, height, x and y are lerped over 190 ms, so a
tap opens the square into the recording capsule instead of swapping rectangles. Two rules keep it from
stuttering: the Compose content crossfade is keyed on the state's *kind* (recording publishes a new
amplitude list ~15×/s, and a state-keyed animation would re-trigger a fade per frame), and the layout
is only re-applied when its destination actually changed, so the service's constant re-evaluation
cannot restart a morph that is still in flight. A successful dictation also no longer hides the orb:
it stays put while the keyboard is up and is taken down by the next evaluation once it is not, which
removes the blink-away-and-back that used to follow every insert.

### 3. Text injection

1. Locate the focused editable node, retried for ~0.7 s — an IME swap briefly clears focus, and the
   only cost of waiting is that the pill says "Inserting text…" a little longer. Four lookups are
   tried: `findFocus(FOCUS_INPUT)` across every window, the same question asked inside the active
   window, a tree walk of that window, and finally the same question asked of every *other* visible
   window. Every route rejects nodes that live in the keyboard's own windows, so a dictation can never
   be typed into an IME's search box instead of the app — which looks exactly like "my text never
   appeared".
2. `performAction(ACTION_SET_TEXT, Bundle(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))`. Fast, and the
   user's clipboard is never touched.
3. If that returns `false` (WebViews, hardened apps, some Compose fields): save the clipboard, copy
   the text in, `ACTION_PASTE`, then **restore the original clipboard** (or clear it if Android
   refused to share it, which it does from Android 10 for background apps).
4. **If there is no field, or both actions are refused, the dictated text is left on the clipboard**
   and the pill says `Copied — long-press to paste.` The words are the whole point of the feature, so
   a failure to deliver them to a field is not a reason to make them disappear; a bare "couldn't
   insert" left the user with nothing at all to show for the dictation.

---

## Project layout

```
app/src/main/java/com/typorb/
├── TyporbApp.kt              Application + manual DI entry point
├── TyporbContainer.kt        Object graph (lazy engines, shared scope)
├── audio/                    AudioRecorder (16k/16-bit/mono PCM), WavEncoder
├── cloud/                    CloudTextProcessor, Retrofit Groq API, DTOs, error mapping
├── local/                    WhisperOnnxEngine, LocalTextProcessor, LocalTextCleaner
│   ├── dsp/                  FFT, Slaney mel filterbank, log-mel front-end
│   └── tokenizer/            Byte-level BPE decoder
├── data/                     SettingsRepository (encrypted), ModelCatalog, ModelRepository (download)
├── domain/                   DictationRequest/Coordinator, engine interface, errors
├── model/                    ProcessingEngine, ContextMode, OverlayUiState
├── overlay/                  OverlayController (WindowManager host)
├── service/                  AccessibilityService, ImeDetector, TextInjector, geometry
├── ui/
│   ├── dashboard/            Launcher screen + ViewModel (settings, permissions, model download)
│   ├── overlay/              Compose pill (idle / recording / processing)
│   └── theme/                Palette, Material 3 scheme, typography
└── util/                     Haptics, Permissions, retry helper
```

**Design notes**

* **MVVM + StateFlow everywhere.** `DictationCoordinator` owns the state machine and exposes a single
  `StateFlow<OverlayUiState>`; the overlay only renders it and forwards taps. The dashboard uses a
  `AndroidViewModel` over the same settings repository.
* **Coroutines, never threads.** `AudioRecord` reads on `Dispatchers.IO`, ONNX inference on
  `Dispatchers.Default`, `performAction` off the main thread, and the service runs everything in a
  `SupervisorJob` scope so one failed dictation can't kill the service.
* **Manual DI, no annotation processing** — a small object graph does not justify Hilt/KSP in a
  service-driven app, and it keeps the CI build fast.
* **Secrets** — the Groq key lives in `EncryptedSharedPreferences` (AES256-GCM, Keystore-backed),
  is excluded from backup, and is never rendered back into the UI.

---

## Engines

### ⚡ Cloud (Groq)

| Step | Call |
| --- | --- |
| Transcription | `POST /openai/v1/audio/transcriptions`, multipart WAV, `whisper-large-v3` |
| Formatting | `POST /openai/v1/chat/completions`, `llama-3.1-8b-instant` |

The `language` field is **omitted by default**, which asks Whisper to detect the language of each
recording — the setting that handles Hinglish, where one sentence mixes Hindi and English. Control →
*Spoken language* can force `en` or `hi` when detection guesses wrong.

The system prompt removes fillers (`umm`, `uh`, `aah`, …), fixes punctuation and reshapes the text
into the selected mode — Quick Chat, Code/Bug Report (markdown), Bullet Notes or Formal Email. Both
calls retry up to 3 times with exponential backoff **only** for rate limits, 5xx and network errors;
a 401 (bad key) fails immediately with a message instead of retrying. If formatting ever returns
nothing, the raw transcript is used rather than losing the user's words.

### ✈️ Local (ONNX Runtime) — downloaded in-app

The weights are **fetched by the app itself** (dashboard → *Offline model*), so a normal install is
~85 MB instead of ~230 MB and nothing binary lives in the repository:

| Variant | Encoder | Decoder | Total |
| --- | --- | --- | --- |
| **Int8 (default)** | 10.1 MB | 30.7 MB | **~40 MB** |
| Float32 | 32.9 MB | 118.6 MB | ~150 MB |

`ModelRepository` streams each file straight to app-private storage (never buffered — a 30 MB byte
array would be fatal on a 3 GB phone), computes SHA-256 **while streaming**, compares it against the
digest pinned in `ModelCatalog`, and only then renames the `.part` file into place. Truncated or
corrupt transfers can therefore never be loaded as a model; a failed download cleans up after itself
and stays retryable. Files in `assets/whisper/` are used as a fallback when nothing is downloaded
(`sh tools/fetch-whisper-model.sh`).

Which files to load is re-resolved on **every dictation**, so a model downloaded while Typorb is
running is picked up by the next take — an asset-backed fp32 session can no longer stay resident for
the life of the process and quietly ignore the 40 MB int8 model the user just fetched. The engine
auto-detects the graph shape, and its decoder prompt starts from the Spoken language setting's token
(`<|en|>` or `<|hi|>`) because a quantised tiny checkpoint has no reliable language-detection pass of
its own. The front-end is implemented here: PCM → 400-sample Hann window, zero-padded to a 512-point radix-2 FFT,
160-sample hop, reflect-padded centre → 80-bin **area-normalised** Slaney mel filterbank → `log10`
with a `max − 8` floor → `(x + 4) / 4` normalisation, zero-padded to the 30 s window. Decoding is
greedy with the KV cache the export provides (re-running the full prefix when it doesn't), and token
ids become text through a byte-level BPE reader that merges `model.vocab` **and** `added_tokens` from
`tokenizer.json`.

### Performance & low-end devices

Tuned for budget hardware (Redmi 8A: Snapdragon 439, 4× A53 @ 1.8 GHz, 3 GB RAM, Adreno 505):

* **Int8 weights** — ~2× faster than fp32 on a CPU-only device and 4× smaller.
* **XNNPACK EP** — roughly 2× the stock kernels for f32 GEMM/MVN on Arm.
* **Thread budget** — `cores - 1`, capped at 4 (`SessionTuning.intraOpThreads`), so the keyboard,
  overlay and target app never starve while inference runs. Inter-op stays sequential.
* **Optimised-graph cache** — ORT's optimisation pass is written to `filesDir/onnx-opt/` and reused,
  which removes seconds of start-up cost on an A53.
* **Duration-aware decode budget** — the greedy loop is capped from the recording length
  (48 steps for a 2 s take up to Whisper's own 448 for a near-30 s one), bounding worst-case latency.
* **GPU off by default** — NNAPI is opt-in in the dashboard. On Adreno 5xx/PowerVR it either fails to
  compile the graph or silently runs a slower CPU path than XNNPACK; it is gated to Android 10+ and
  always falls back to CPU on failure.
* **Memory** — `minSdk 26`, no multidex requirement, encoder output tensor is reused across decode
  steps instead of being copied.

See [`app/src/main/assets/whisper/README.md`](app/src/main/assets/whisper/README.md) for the exact
tensor contract.

---

## Build

```bash
./gradlew assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest       # 131 unit tests (see below)
sh tools/fetch-whisper-model.sh   # optional: bake the weights in for offline dev
```

Unit tests cover the pieces that can be proved without a device: what counts as an editable field and
when the orb is allowed to exist (the two rules its appearance turns on), pill geometry, transcript
cleanup, the FFT/mel front-end
(frame counts, filterbank weighting, tone placement), the byte-level BPE decoder, the model-download
integrity gate (SHA-256 accepts the real digest and rejects truncated files), the overlay window's
per-state rectangles and morph interpolation, the language tokens both engines share, the dictation
rows the debug console reports, and the device-tuning heuristics. A smoke test also runs against the
real vocabulary whenever the model assets are present, and skips otherwise.

Requires JDK 17. `compileSdk 34`, `minSdk 26`, `targetSdk 34`, AGP 8.5.2, Kotlin 1.9.24, Compose BOM
2024.06.

### CI

[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) runs on every push to `main`
(ubuntu-latest, Temurin JDK 17, Android SDK, cached Gradle dependencies), runs
`./gradlew assembleDebug --no-daemon`, and uploads **`app-debug.apk`** as a downloadable workflow
artifact.

---

## Installing the APK — and Play Protect

Sideloading Typorb raises a Play Protect warning ("Play Protect doesn't recognize this app's
developer", with *Install anyway* sometimes behind *More details*). It is not a detection of
anything in the app: Android shows it for **any** app installed from outside a store whose signing
key Google has no history for. Typorb meets it more often than most because its permissions —
accessibility, overlay, microphone — are exactly the ones malware asks for, and because every release
so far was signed with a throwaway key generated inside CI (see the workflow header).

**Install anyway (works today)**

1. Tap **More details → Install anyway**. If MIUI's own scanner warns afterwards, tap
   **Install anyway / Continue** there too.
2. If the dialog only offers *Don't install*, turn scanning off for the install and back on after:
   Play Store → profile → Play Protect → gear icon → **Scan apps with Play Protect**. MIUI puts the
   same toggle under Settings → Google → *Settings for Google apps*.
3. **Uninstall the previous Typorb first.** Every release so far carries a different signature, so
   Android refuses to upgrade in place.

**The actual fix — one stable signing key**

```bash
sh tools/generate-signing-key.sh          # writes the keystore + the four secret values
```

Add the four secrets it prints (`TYPORB_KEYSTORE_BASE64`, `TYPORB_STORE_PASSWORD`,
`TYPORB_KEY_ALIAS`, `TYPORB_KEY_PASSWORD`). From the next release on, every build is signed by the
same developer: it installs over the previous one instead of demanding an uninstall, and Play
Protect sees one identity rather than a new one per build.

**Paths that never raise the warning**

* `adb install -r app-release.apk` — installs from a PC and skips Play Protect's install-time block
  entirely (the on-device scan still runs afterwards).
* **Google Play internal testing** — one-time $25 developer account, an internal test track, and Play
  distributes and signs the app itself (Play App Signing). Play-distributed apps are trusted by Play
  Protect by construction, and no key has to be generated or stored locally.

**Android developer verification (starting 30 September 2026)**

Protections began rolling out on 30 September 2026 for apps from participating stores in select
regions on certified Android devices, expanding globally through 2027
([developer.android.com/developer-verification](https://developer.android.com/developer-verification)).
Registration ties a package name (`com.typorb`) to a **signing key** and a verified developer
identity, so the stable key above is the prerequisite either way. Personal use fits a **limited
distribution account** (up to 20 devices, no government ID, no registration fee), and power users can
keep installing unverified apps through the advanced flow. Apps distributed outside Play register in
the Android Developer Console; Play Console covers Play apps automatically.

## When the orb does not appear

The Settings → Debug console answers this from the device itself — read the verdict line first, then
`Orb watchdog`, `Keyboard window found`, `Orb show attempts` and `Orb window error`:

| Symptom | Cause | Fix |
| --- | --- | --- |
| `Accessibility service: no` | Disabled, or killed by the battery optimiser | Re-enable it, then lock Typorb in Recents |
| Verdict "Keyboard not detected" | Signal is not reaching the service | Check `IME probe window`; grant "Display over other apps" and restart the phone |
| Verdict "Overlay window not added" | WindowManager refused the orb | The verbatim error is printed below the rows; grant "Display over other apps" so both window types can be tried |
| Watchdog stops climbing | Service killed in background | Settings → Apps → Typorb → Battery → **No restrictions**, and enable **Autostart** |

## When no text appears in the field

The debug console has a second half for this — `Last dictation`, `Audio captured`, `Transcript size`
and `Injection` — because the orb appearing and the words arriving are different pipelines. Read the
stage first: it names exactly how far the take got.

| Console row | Meaning | Fix |
| --- | --- | --- |
| `Last dictation: recording` | Still recording — a take ends on the **second** tap | Tap the orb again |
| `Audio captured: 0 ms` | Microphone produced nothing | Grant microphone permission; check MIUI's Privacy → Microphone log |
| `Last dictation: failed · CLOUD` + `dictation error` | Groq refused or was unreachable | The error is printed verbatim; a 401 means the key, not the network |
| `Last dictation: failed · LOCAL` | Offline model missing or too slow | Download the int8 model (Settings → *Offline model*); the bundled fp32 weights are many times slower on an A53 |
| `Injection: COPIED_TO_CLIPBOARD` | No editable field could be reached | The text is on the clipboard — long-press the field and paste |
| `Injection: NONE` | Field refused both routes | Check `Text field focused` / `Focus lookup`; the field may be a canvas or a game |

**Copy report** copies all of it, including the verbatim error, so a bug report carries the evidence
rather than a description of the symptom.

MIUI/HyperOS specifics on the Redmi 8A class of devices:

* **Battery** → Apps → Typorb → *No restrictions*. MIUI otherwise stops accessibility services after a
  while, which looks exactly like "the orb never appears".
* **Autostart** → enable Typorb, and lock it in Recents (the padlock on the app card).
* **Developer options → MIUI optimization** can block overlay windows on some builds; toggling it off
  is a last resort, not a requirement.
* Permissions → **Display pop-up windows while running in background** must be on for the fallback
  overlay type to be usable.

## Permissions

| Permission | Why |
| --- | --- |
| Accessibility service | Detects focused text fields, measures the IME window and injects text. The only truly required one. |
| Microphone | Captures 16 kHz audio. |
| Internet | Cloud mode only. |
| Display over other apps | Recommended — some OEM skins suppress accessibility overlays. |

## Privacy

Local mode never touches the network. Cloud mode sends only the recorded audio plus the transcript to
Groq, and the API key never leaves the device's Keystore-backed storage.