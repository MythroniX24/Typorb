package com.typorb.data

/**
 * The Whisper `tiny` ONNX weights Typorb can fetch at runtime, with the exact integrity data needed
 * to trust what lands on disk.
 *
 * Sizes and SHA-256 digests are pinned to the files published by `onnx-community/whisper-tiny`
 * (encoder + merged decoder) and `openai/whisper-tiny` (tokenizer). Int8 is the default: on a
 * Snapdragon 439 class CPU it is both ~4× smaller and ~2× faster than fp32, which is what makes
 * offline dictation viable on a budget phone.
 */
object ModelCatalog {

    private const val HF = "https://huggingface.co"
    private const val MODEL_REPO = "$HF/onnx-community/whisper-tiny/resolve/main/onnx"
    private const val TOKENIZER_URL = "$HF/openai/whisper-tiny/resolve/main/tokenizer.json"

    /** A single downloadable file with its integrity metadata. */
    data class ModelFile(
        val name: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    data class Variant(
        val id: String,
        val label: String,
        val blurb: String,
        val encoder: ModelFile,
        val decoder: ModelFile,
        val tokenizer: ModelFile,
    ) {
        val files: List<ModelFile> get() = listOf(encoder, decoder, tokenizer)
        val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    }

    val INT8 = Variant(
        id = "int8",
        label = "Int8 (recommended)",
        blurb = "~40 MB. Quantised — fastest and smallest, ideal for budget phones.",
        encoder = ModelFile(
            name = "whisper-tiny.onnx",
            url = "$MODEL_REPO/encoder_model_quantized.onnx",
            sizeBytes = 10_124_990L,
            sha256 = "2af4a414ca47aa30f61246017e5fe82b0a8d229281d1255ba666a2a7f6b84d19",
        ),
        decoder = ModelFile(
            name = "whisper-tiny-decoder.onnx",
            url = "$MODEL_REPO/decoder_model_merged_quantized.onnx",
            sizeBytes = 30_719_241L,
            sha256 = "25e807a962b6349356d0ea5d0dfe530b7e5bf0e2a484aeca0359d03143faddd3",
        ),
        tokenizer = ModelFile(
            name = "tokenizer.json",
            url = TOKENIZER_URL,
            sizeBytes = 2_480_466L,
            sha256 = "27fc476bfe7f17299480be2273fc0608e4d5a99aba2ab5dec5374b4482d1a566",
        ),
    )

    val FP32 = Variant(
        id = "fp32",
        label = "Float32 (largest quality)",
        blurb = "~150 MB. Full precision — marginally better wording, much heavier.",
        encoder = ModelFile(
            name = "whisper-tiny.onnx",
            url = "$MODEL_REPO/encoder_model.onnx",
            sizeBytes = 32_904_992L,
            sha256 = "6642befb640f950d4a8cbbd17834d59e7e75f575b81ccf213e06b050623ab1dd",
        ),
        decoder = ModelFile(
            name = "whisper-tiny-decoder.onnx",
            url = "$MODEL_REPO/decoder_model_merged.onnx",
            sizeBytes = 118_553_827L,
            sha256 = "8d20f4157407006e871d63ca0a3c54dddd7db33dfc4ee076960f1f8b2763ce3e",
        ),
        tokenizer = INT8.tokenizer,
    )

    val variants: List<Variant> = listOf(INT8, FP32)

    /** The variant offered first in the UI. */
    val recommended: Variant = INT8

    fun variant(id: String?): Variant = variants.firstOrNull { it.id == id } ?: recommended

    /** File names are shared across variants, so each variant owns its own directory. */
    fun directoryName(variant: Variant): String = "models/${variant.id}"
}