#!/usr/bin/env sh
# Fetches the Whisper `tiny` ONNX export used by Typorb's offline (✈️ Local) engine.
#
#   sh tools/fetch-whisper-model.sh [target-dir]
#
# This is only needed for offline development: the app downloads and verifies these same files itself
# at runtime (see ModelCatalog). Use it to test the offline path without waiting for an in-app
# download, or to bake them into a special build.
#
# Defaults to the int8 export (~40 MB) — the same build the app recommends. Set QUANT=0 for fp32.
set -eu

TARGET_DIR="${1:-app/src/main/assets/whisper}"
MODEL_REPO="${WHISPER_MODEL_REPO:-onnx-community/whisper-tiny}"
TOKENIZER_REPO="${WHISPER_TOKENIZER_REPO:-openai/whisper-tiny}"
HF_BASE="${HF_BASE:-https://huggingface.com}"
QUANT="${QUANT:-1}"

if [ "$QUANT" = "1" ]; then
    ENCODER_FILE="encoder_model_quantized.onnx"
    DECODER_FILE="decoder_model_merged_quantized.onnx"
else
    ENCODER_FILE="encoder_model.onnx"
    DECODER_FILE="decoder_model_merged.onnx"
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "error: curl is required" >&2
    exit 1
fi

mkdir -p "$TARGET_DIR"

fetch() {
    url="$1"
    out="$2"
    printf 'downloading %s\n' "$out"
    curl -fL --retry 3 --retry-delay 2 --progress-bar "$url" -o "$out"
}

fetch "$HF_BASE/$MODEL_REPO/resolve/main/onnx/$ENCODER_FILE" \
      "$TARGET_DIR/whisper-tiny.onnx"
fetch "$HF_BASE/$MODEL_REPO/resolve/main/onnx/$DECODER_FILE" \
      "$TARGET_DIR/whisper-tiny-decoder.onnx"
fetch "$HF_BASE/$TOKENIZER_REPO/resolve/main/tokenizer.json" \
      "$TARGET_DIR/tokenizer.json"

printf '\noffline model ready:\n'
ls -lh "$TARGET_DIR"
printf '\nRebuild with:  ./gradlew assembleDebug\n'