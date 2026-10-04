package com.typorb.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.File
import java.security.MessageDigest

/**
 * The download path is the one part of the offline engine that can be fully exercised on the JVM:
 * the catalog's integrity metadata and the SHA-256 gate that decides whether a file is allowed to
 * become a model.
 */
class ModelRepositoryTest {

    @Test
    fun `catalog ships at least one variant and recommends the small one`() {
        assertTrue(ModelCatalog.variants.isNotEmpty())
        assertEquals("int8", ModelCatalog.recommended.id)
        assertTrue(ModelCatalog.variant(null).id == ModelCatalog.recommended.id)
        assertTrue(ModelCatalog.variant("nonsense").id == ModelCatalog.recommended.id)
        assertEquals(ModelCatalog.FP32.id, ModelCatalog.variant("fp32").id)
    }

    @Test
    fun `int8 is dramatically smaller than fp32`() {
        val int8 = ModelCatalog.INT8.totalBytes
        val fp32 = ModelCatalog.FP32.totalBytes
        assertTrue("int8 should be well under a third of fp32", int8 * 3 < fp32)
        assertTrue(int8 in 30_000_000L..50_000_000L)
    }

    @Test
    fun `every file has a plausible url, size and digest`() {
        ModelCatalog.variants.forEach { variant ->
            assertTrue(variant.files.size == 3)
            variant.files.forEach { file ->
                assertTrue("${file.name} url", file.url.startsWith("https://"))
                assertTrue("${file.name} size", file.sizeBytes > 0)
                assertEquals("${file.name} sha256 length", 64, file.sha256.length)
                assertTrue(
                    "${file.name} sha256 is hex",
                    file.sha256.all { it.isDigit() || it in 'a'..'f' },
                )
            }
            assertEquals(
                variant.totalBytes,
                variant.files.sumOf { it.sizeBytes },
            )
        }
    }

    @Test
    fun `tokenizer is shared between variants`() {
        assertEquals(ModelCatalog.INT8.tokenizer.sha256, ModelCatalog.FP32.tokenizer.sha256)
    }

    @Test
    fun `isVerified accepts the real digest and rejects everything else`() {
        val file = File.createTempFile("typorb", ".onnx")
        try {
            file.writeBytes("hello offline typing".toByteArray())
            val digest = sha256(file.readBytes())

            assertTrue(ModelRepository.isVerified(file, digest))
            assertTrue("case insensitive", ModelRepository.isVerified(file, digest.uppercase()))
            assertFalse(
                "wrong digest",
                ModelRepository.isVerified(file, "0".repeat(64)),
            )
            assertFalse("empty file", ModelRepository.isVerified(File(file.parent, "missing.bin"), digest))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a truncated download fails verification`() {
        val full = File.createTempFile("typorb-full", ".onnx")
        val truncated = File.createTempFile("typorb-cut", ".onnx")
        try {
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            full.writeBytes(bytes)
            truncated.writeBytes(bytes.copyOf(bytes.size / 3))
            val fullDigest = sha256(bytes)

            assertTrue(ModelRepository.isVerified(full, fullDigest))
            assertFalse(
                "a partial file must never be treated as verified",
                ModelRepository.isVerified(truncated, fullDigest),
            )
        } finally {
            full.delete()
            truncated.delete()
        }
    }

    @Test
    fun `progress fractions are clamped`() {
        val state = OfflineModelState.Downloading(
            currentFile = "whisper-tiny.onnx",
            fileBytes = 50,
            fileTotalBytes = 100,
            bytesDone = 150,
            bytesTotal = 400,
        )
        assertEquals(0.5f, state.fileProgress, 1e-6f)
        assertEquals(0.375f, state.overallProgress, 1e-6f)

        val unknownLength = OfflineModelState.Downloading(
            currentFile = "tokenizer.json",
            fileBytes = 1024,
            fileTotalBytes = 0,
            bytesDone = 0,
            bytesTotal = 0,
        )
        assertEquals(0f, unknownLength.fileProgress, 1e-6f)
        assertEquals(0f, unknownLength.overallProgress, 1e-6f)
    }

    @Test
    fun `ready and corrupt states are distinguishable`() {
        val ready: OfflineModelState = OfflineModelState.Ready(100, 98)
        val corrupt: OfflineModelState = OfflineModelState.Corrupt("bad digest")
        assertTrue(ready is OfflineModelState.Ready)
        assertTrue(corrupt is OfflineModelState.Corrupt)
    }

    /**
     * A corrupt body from a CDN edge is not a dead connection.
     *
     * Both arrive as [java.io.IOException], so treating every IOException as non-retryable hid the
     * retry button for the one failure where retrying actually helps.
     */
    @Test
    fun `integrity failures are distinguished from ordinary IO failures`() {
        val integrity = ModelIntegrityException("Integrity check failed for whisper-tiny.onnx")
        assertTrue(integrity is java.io.IOException)
        assertTrue(
            "integrity failures must be retryable",
            integrity !is java.io.IOException || integrity is ModelIntegrityException,
        )

        val outOfSpace = java.io.IOException("No space left on device")
        assertFalse(
            "a full disk is not fixed by retrying",
            outOfSpace !is java.io.IOException || outOfSpace is ModelIntegrityException,
        )
    }

    /**
     * The pinned digests are the contract with Hugging Face; if any drifts the download fails its
     * integrity check on every user's device and there is no way to recover in-app.
     */
    @Test
    fun `catalog digests match the files actually published upstream`() {
        // Digests captured from huggingface.co for onnx-community/whisper-tiny and
        // openai/whisper-tiny. Re-verify when bumping the catalog.
        val expected = mapOf(
            "whisper-tiny.onnx|int8" to "2af4a414ca47aa30f61246017e5fe82b0a8d229281d1255ba666a2a7f6b84d19",
            "whisper-tiny-decoder.onnx|int8" to "25e807a962b6349356d0ea5d0dfe530b7e5bf0e2a484aeca0359d03143faddd3",
            "whisper-tiny.onnx|fp32" to "6642befb640f950d4a8cbbd17834d59e7e75f575b81ccf213e06b050623ab1dd",
            "whisper-tiny-decoder.onnx|fp32" to "8d20f4157407006e871d63ca0a3c54dddd7db33dfc4ee076960f1f8b2763ce3e",
            "tokenizer.json|int8" to "27fc476bfe7f17299480be2273fc0608e4d5a99aba2ab5dec5374b4482d1a566",
            // FP32 reuses the int8 tokenizer entry, but it is still enumerated under its own id.
            "tokenizer.json|fp32" to "27fc476bfe7f17299480be2273fc0608e4d5a99aba2ab5dec5374b4482d1a566",
        )
        ModelCatalog.variants.forEach { variant ->
            variant.files.forEach { file ->
                val key = "${file.name}|${variant.id}"
                assertEquals(
                    "digest drifted for $key",
                    expected.getValue(key),
                    file.sha256,
                )
            }
        }
    }

    @Test
    fun `catalog sizes match the published file sizes`() {
        assertEquals(10_124_990L, ModelCatalog.INT8.encoder.sizeBytes)
        assertEquals(30_719_241L, ModelCatalog.INT8.decoder.sizeBytes)
        assertEquals(32_904_992L, ModelCatalog.FP32.encoder.sizeBytes)
        assertEquals(118_553_827L, ModelCatalog.FP32.decoder.sizeBytes)
        assertEquals(2_480_466L, ModelCatalog.INT8.tokenizer.sizeBytes)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}