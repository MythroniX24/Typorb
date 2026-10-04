# Offline model assets

Typorb's **Local (✈️ Offline) engine** does its own model download: open the app, go to
**Offline model**, pick a variant and press **Download**. The files are streamed straight to
app-private storage, **SHA-256 verified**, and only then promoted into place.

| Variant | Encoder | Decoder | Tokenizer | Total |
| --- | --- | --- | --- | --- |
| **Int8 (recommended)** | 10.1 MB | 30.7 MB | 2.5 MB | **~40 MB** |
| Float32 | 32.9 MB | 118.6 MB | 2.5 MB | ~150 MB |

Int8 is the default because it is both ~4× smaller and markedly faster on a CPU-only budget phone
(Redmi 8A class: Snapdragon 439, four A53 cores, no usable NNAPI).

## This folder

Files placed here are used as a **fallback** when nothing has been downloaded — handy for local
development and for producing a self-contained build:

```sh
sh tools/fetch-whisper-model.sh        # int8, ~40 MB
QUANT=0 sh tools/fetch-whisper-model.sh # fp32, ~150 MB
```

They are gitignored, so a normal clone ships a Cloud-only app (~85 MB, dominated by the ONNX Runtime
native libraries) and the weights arrive only if the user asks for them.

## Tensor contract

`WhisperOnnxEngine` resolves tensor names at load time, so both published shapes work:

**Encoder** — `input_features` `[1, 80, 3000]` → `last_hidden_state` `[1, 1500, 384]`

**Decoder (merged)** — inputs `input_ids` `[1, T]`, `encoder_hidden_states`, `use_cache_branch`
(bool) and, after the first step, `past_key_values.N.{encoder,decoder}.{key,value}`; outputs `logits`
`[1, T, 51865]` and `present.N.{encoder,decoder}.{key,value}`.

A **fused** single-file export is also supported: when one file carries both the mel input and the
decoder token input, the engine feeds `input_features` to the decoder directly and skips the encoder.
Exports without a KV cache work too — the whole token prefix is re-run each step instead.

`input_features` is always the full 30-second window; short takes are zero-padded because Whisper's
encoder convolutions are anchored to that length. Output precision may be float32 or float16.

## A note on `tokenizer.json`

`model.vocab` alone is not enough. Whisper stores the GPT-2 base there (50 258 entries) and puts its
control tokens *and* its multilingual tokens in `added_tokens`, with ids running contiguously up to
51 864 — 51 865 tokens in total. `ByteLevelBpeTokenizer` merges both halves; ignoring `added_tokens`
collapses the decode prompt to `<|endoftext|>` and silently drops every non-ASCII token.