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
└──────────────────────────────────────────────────────────────────────────┘
```

### 1. When does Typorb appear?

Both conditions must hold, checked on every focus/click/window-change event:

1. **An editable field owns input focus** — `AccessibilityNodeInfo.isEditable`, plus a class-name
   check for `EditText` / `AutoCompleteTextView` / `WebView` (the `isTextEditable` flag is a hidden
   API and is deliberately not used).
2. **The soft keyboard is on screen** — the IME window is located by package in
   `AccessibilityService.getWindows()`, and its height comes from the root node's screen rectangle
   (`AccessibilityWindowInfo.getBounds` is hidden too). A floating/split keyboard counts as "no
   keyboard". `WindowInsetsCompat.Type.ime()` is wired as a secondary signal for OEMs that do not
   expose the IME window.

If either condition drops, the pill animates out immediately — including while it is recording.

### 2. The Typorb lifecycle

| State | Look | What happens |
| --- | --- | --- |
| Idle | 48dp rounded square (14dp radius), frosted glass, softly pulsing mic | Tap → start recording, `EFFECT_TICK` |
| Recording | 160dp capsule, 5 canvas bars driven by live RMS decibels | Tap → stop, waveform freezes, morph to processing |
| Processing | Rotating `Brush.sweepGradient` border, cascading ellipsis, stage label | `ACTION_SET_TEXT` (or clipboard paste), `EFFECT_CLICK` |
| Failed | Red capsule with the reason | Auto-collapses after 2.6s |

### 3. Text injection

1. Locate the focused editable node (retried a few times — an IME swap briefly clears focus).
2. `performAction(ACTION_SET_TEXT, Bundle(ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE))`. Fast, and the
   user's clipboard is never touched.
3. If that returns `false` (WebViews, hardened apps, some Compose fields): save the clipboard, copy
   the text in, `ACTION_PASTE`, then **restore the original clipboard** (or clear it if Android
   refused to share it, which it does from Android 10 for background apps).

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

The engine loads whichever is present, prefers the download, and auto-detects the graph shape. The
front-end is implemented here: PCM → 400-sample Hann window, zero-padded to a 512-point radix-2 FFT,
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
./gradlew testDebugUnitTest       # 46 unit tests (see below)
sh tools/fetch-whisper-model.sh   # optional: bake the weights in for offline dev
```

Unit tests cover the pieces that can be proved without a device: pill geometry, transcript cleanup,
the FFT/mel front-end (frame counts, filterbank weighting, tone placement), the byte-level BPE
decoder, the model-download integrity gate (SHA-256 accepts the real digest and rejects truncated
files) and the device-tuning heuristics. A smoke test also runs against the real vocabulary whenever
the model assets are present, and skips otherwise.

Requires JDK 17. `compileSdk 34`, `minSdk 26`, `targetSdk 34`, AGP 8.5.2, Kotlin 1.9.24, Compose BOM
2024.06.

### CI

[`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) runs on every push to `main`
(ubuntu-latest, Temurin JDK 17, Android SDK, cached Gradle dependencies), runs
`./gradlew assembleDebug --no-daemon`, and uploads **`app-debug.apk`** as a downloadable workflow
artifact.

---

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