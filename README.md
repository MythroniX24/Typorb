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

The orb is **a tile carrying the app's icon, with a panel that slides out of its left edge**. The tile is
the orb: it is what the user taps, it never resizes, never changes shape, and it never moves while a state
changes around it. Every state that has something to say says it in the panel beside it, so the resting orb
stays recognisable as Typorb instead of being one more anonymous square floating over the keyboard.

| State | Look | What happens |
| --- | --- | --- |
| Idle | the icon tile on its own (user-sized, 48dp by default, 14dp corners), completely still | Tap → start recording, `EFFECT_TICK` |
| Recording | a 104dp glass panel slides out to the **left** of the tile, carrying five bars that grow up and down from a centre line | Tap → stop; the panel collapses back and the tile keeps its corner |
| Processing | the tile alone, dimmed under a rotating cobalt arc | `ACTION_SET_TEXT` (or clipboard paste), `EFFECT_CLICK` |
| Failed | the panel comes back out, red-tinted and widened to fit the reason | Auto-collapses after 2.6s |

**The waveform is the sentence, and it goes flat when the sentence stops.** The bars are symmetric about
the panel's centre line with a 2dp rail drawn the whole width underneath them. While the user speaks the
bars move up and down; when they stop, the bars shrink onto the rail at exactly its height *and* its
opacity, so a pause is one unbroken straight line rather than a row of stubs shivering at the noise floor.
When a level counts as silence is `WaveformGate`'s decision (at or below 0.16 — above a phone microphone's
noise floor in a quiet room, below speech at arm's length), so it is asserted on the JVM rather than tuned
by ear on a device.

**One number, one clock, two window writes per state change.** A window surface is clipped to its own
bounds, so the pill has to be drawn inside a rectangle at least as big as it is. Earlier revisions tried to
keep the two in step by animating both — a `ValueAnimator` on the window *and* a Compose size transform on
the content — and later by having Compose report the box on **every animation frame** while the controller
wrote the window on each report. Both were races: two clocks that disagree, and a window that for one frame
is still the old rectangle, which is the pill visibly being cut off by its own window on a tap.

There is one number now. The tile's height is the pill's height in every state, so only the **width** is
animated, on one easing curve; the composition reports that *target* to the controller once per state
change; and the controller writes the window twice — first to the **hull** of the pill that is leaving and
the pill that is arriving (so every frame of the morph is already inside it), then, when the morph is over,
to the new pill alone. No frame of any animation writes the window, so no frame can outrun it. The window is
that box plus 14dp of shadow padding on each side, and its **top-right corner is the tile's anchor**: the
panel opens leftwards from it, so the tile — the thing under the user's finger — does not move by a pixel.

**Motion, and what it is for.** A state change morphs the surface on one `FastOutSlowInEasing` clock,
moves the border colour with it, crossfades the contents (the outgoing one leaves in 80ms, the incoming
one arrives after a 70ms beat, so contents are never squeezed inside a capsule that is still growing),
and sweeps a soft indigo sheen across the glass once — the acknowledgement that a tap landed. Nothing
the user is not touching animates: the resting orb is completely still, which matters because the idle
state is the one the user is in when they drag it, and the two infinite transitions that used to run
there (a glow and a geometric "breath") recomposed the overlay on every frame of every drag.

Two rules keep the rest honest. The Compose crossfade is keyed on the state's *kind* (recording
publishes a new amplitude list ~15×/s, and a state-keyed animation would re-trigger a fade per frame),
and the window itself only fades and settles 8dp on the way in — a `View` animation on the render node,
never a scale or a rectangle, because the window's rectangle belongs to the window manager and the drag
reads its origin from it.

### 2b. Moving the orb

**Drag it anywhere, in every direction.** A touch on the orb is tracked in raw screen coordinates, so the
pill follows the finger exactly — measuring the movement *inside* the view would feed the window's own
movement back into the gesture and the orb would trail at half speed. Past the platform's touch slop the
touch is a drag; below it, it is a tap that starts a dictation, which is why the composition never sees a
touch at all and the press feedback is driven from the same handler. While the finger is down the orb
tightens to 94% — and it deliberately does **not** grow while being carried. An orb that inflates under
the finger reads as lag, not as feedback.

The only rule on where it can be dropped is that the pill stays fully on screen, with its shadow
padding, so a drag can never park it half off an edge. That rule used to also stop the orb at the
keyboard's top edge — and because the orb's own resting place is 16dp *above* that edge, the wall sat
one row below its default position: a downward drag moved 14dp and stopped, which does not feel like a
boundary, it feels like a broken drag. The wall is gone. A keyboard cannot hide the orb anyway — this
window is `TYPE_ACCESSIBILITY_OVERLAY`, which is layered above the IME, so an orb parked over the
keyboard is still on top, still visible and still touchable.

The dropped position is written to Settings on release (not per frame), so it survives the window being
recreated, the keyboard opening and closing, and a reboot. Until the user moves it, the position is still
recomputed from the keyboard height, which is what keeps the orb 16dp above the IME as it rises and falls.
Two smaller things make a drag cheap: the screen bounds are cached rather than re-queried (that query is a
binder call, and a drag asks for it up to 120 times a second), and a window write that WindowManager
refuses is rolled back out of the layout params, so the next drag cannot start from a position the orb is
not at.

**A fast drag no longer stalls.** A finger that moves quickly produces more `ACTION_MOVE` events than
there are frames, and the platform delivers them in bursts. Each one used to end in its own
`updateViewLayout` — a binder round trip through the window manager plus the relayout behind it — and once
that work takes longer than the finger takes to move, the backlog feeds itself: stale move events pile up,
every one of them is a transaction the orb does not need, and the orb visibly sticks and then jumps. So a
move is no longer a write, it is a **destination**: everything that arrives inside one frame collapses to
the last position, one frame writes the window once, and the position written is always the newest the
user asked for, never a replayed intermediate the finger has already left. Nothing is delayed by it either
— a window position only reaches the screen at a frame boundary, so a write on the next frame shows up
exactly when a write on the event would have.

While a finger is on the orb, the 600 ms accessibility watchdog **skips its tick**. A tick is a pair of
accessibility queries — `findFocus`, the window list — and those are binder calls of their own on the same
thread that has to hand the drag its next position, so one of them landing mid-drag is a dropped frame.
Nothing is lost: a keyboard does not appear or disappear while a finger is dragging an orb, and the next
tick after the finger lifts evaluates normally. That, plus the coalescing above, is the difference between
a drag that is merely slow and one that keeps getting stuck.

**Long press** the orb to type the last transcript again, without saying it a second time. It is the
escape hatch for the worst failure this app has: words that were captured, transcribed, and then refused
by the field. The pill reports the retry exactly like a normal dictation — same failure message, same
clipboard fallback — and it is ignored while a take is running, because the press belongs to the
recording at that point.

The long press is 700ms, well past the platform's own 500ms timeout, and that is deliberate: a long
press and a drag begin with the same gesture and are only told apart by the clock, so the long press has
to give a hesitant drag every chance to become one. At the old 420ms — *under* the platform timeout — a
finger that rested on the orb before moving was refused a drag for the rest of the gesture, and a long
press that had already fired now blocks nothing either. The drag is the daily gesture; the retype is a
rescue. When they collide, the drag wins.

### 3. Text injection

1. Locate the focused editable node, retried for ~0.7 s — an IME swap briefly clears focus, and the
   only cost of waiting is that the pill says "Inserting text…" a little longer. Four lookups are
   tried: `findFocus(FOCUS_INPUT)` across every window, the same question asked inside the active
   window, a tree walk of that window, and finally the same question asked of every *other* visible
   window. Every route rejects nodes that live in the keyboard's own windows, so a dictation can never
   be typed into an IME's search box instead of the app — which looks exactly like "my text never
   appeared". The field captured when the orb was tapped is the fallback for when the keyboard has
   closed since, not the first choice: it is re-resolved at commit time, because a node held across
   several seconds of dictation routinely goes stale.
2. **The platform's own input connection** on Android 13+, where the accessibility service is granted
   one — the same `commitText` a keyboard uses, straight into the field at the caret, with no node and
   no clipboard.
3. `performAction(ACTION_SET_TEXT, Bundle(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))` — but *only* for a
   field whose current content could actually be read. `ACTION_SET_TEXT` replaces the whole field, so
   rebuilding one around text nobody read is how a placeholder ends up inside the user's message.
   What is read is kept, so a dictation into a half-written message **adds to it** instead of replacing
   it. An **empty** field counts as read, and that is the case that matters: `getText()` returns `null`
   for a field holding nothing — the fresh chat box, the empty search field, the note with no first word,
   which are the fields this app is opened on — and `null` used to be read as "this field will not say",
   which switched the strongest route off exactly where it was best. Joining an empty field with a
   transcript is the transcript, so a field proven empty is the *safest* rebuild there is. A password
   field is the one genuine exception: it never reports its contents, so it keeps the insertive routes
   only.
4. **`ACTION_PASTE`** from the clipboard: it inserts at the field's own caret, so it is structurally
   incapable of inventing or deleting text. The original clipboard is restored afterwards (or cleared
   if Android refused to share it, which it does from Android 10 for background apps).
5. **`ACTION_FOCUS` then repeat**, for the fields that refuse everything until they hold focus
   themselves — WhatsApp's message box is the canonical one.
6. **If there is no field, or every route is refused, the dictated text is left on the clipboard** and
   the pill says `Copied — long-press to paste.` The words are the whole point of the feature, so a
   failure to deliver them to a field is not a reason to make them disappear.

Every check on the result starts with `AccessibilityNodeInfo.refresh()`. A node is a *snapshot*: its
`.text` is the field's content from when the node was fetched, and `performAction` does not update it.
Reading it straight after a write therefore answers with the value from *before* the write — which is
how a successful set-text was recorded as "the field did not change" and the same sentence was written a
second time by the next route.

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
├── overlay/                  OverlayController (WindowManager host), drag host + rules, geometry,
│                             waveform silence gate
├── service/                  AccessibilityService, ImeDetector, TextInjector, geometry
├── ui/
│   ├── dashboard/            Launcher screen + ViewModel (settings, permissions, model download)
│   ├── overlay/              Compose orb — the icon tile and its side panel
│   ├── theme/                Palette, shapes, elevation, type
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
./gradlew testDebugUnitTest       # 159 unit tests (see below)
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

## When the words do not appear

A dictation that does not land is the one failure that is completely silent: the pill says something
reassuring, the transcript is saved to history, and the text box is unchanged. So every injection
route is *verified* rather than trusted, because `ACTION_SET_TEXT` returning `true` only means the
action was performed — several apps (WhatsApp's message box among them) perform it and drop the text.

The pipeline, in order, each step tried only after the last one was checked:

1. **The accessibility input connection** (Android 13+): the field is written through the platform's own
   editor connection, exactly like a keyboard.
2. **`ACTION_SET_TEXT`** — only for a field whose contents could be read, and the existing text is kept,
   so a dictation into a half-written message **adds to it** instead of replacing it.
3. **`ACTION_PASTE`** from the clipboard, with the original clipboard restored afterwards.
4. **`ACTION_FOCUS` then the same two routes again**, for fields that refuse anything until they hold
   focus themselves.
5. If all of that fails the words are **left on the clipboard** and the pill says
   `Copied — long-press to paste.` The text is never silently dropped.

Two rules make that ladder land more often without ever doubling a sentence. The read-back asks **two**
questions — what the node the write went to says, and what the field the *system* is pointing at right
now says — because the write's own node can have gone stale, and a stale node used to report a
successful write as a failure and send the pipeline round the whole ladder a second time. And the ladder
is retried (once, after 260ms) **only when no route reported a write at all**: a field that was not
ready yet is worth one more pass, while a field that took the text and hid it is not, because writing
again on top of a write the field will not confirm is how one dictation arrives twice. When something
was written and cannot be confirmed, the text goes to the clipboard intact and the pill says so.

**Then long-press the orb to type the same words again.** The last transcript is kept for exactly this:
if the field refused it, you do not have to say the sentence twice. The retry runs the same pipeline and
reports the same way, so it costs nothing and recovers from a refusal that had nothing to do with the
recording.

Settings → Debug console reports the route, the field it went into, and the exact reason: `Injection`
names which step won (`A11Y_IME`, `ACTION_SET_TEXT`, `CLIPBOARD_PASTE`, `FOCUS_THEN_SET_TEXT`,
`COPIED_TO_CLIPBOARD`) followed by `EditText in com.whatsapp · live focus (system findFocus)` or, when
something went wrong, which steps were refused and why.

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
| `Injection: COPIED_TO_CLIPBOARD` | No editable field could be reached | The text is on the clipboard — long-press the field and paste, or **long-press the orb to type it again** |
| `Injection: NONE` | Every route was refused | Check `Text field focused` / `Focus lookup`; the field may be a canvas or a game. Long-press the orb to retry once it is a real text box |
| `Injection: … · EditText in com.android.systemui` | The text went into the wrong field — a system window claimed focus | Tap your app's field, then **long-press the orb** to type the same words there |

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