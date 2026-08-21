package com.peter.mutter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * Exercises the real download against the real URLs — the part most likely to
 * rot without anyone noticing, because an upstream file that moves or is
 * re-uploaded turns into an opaque failure on a stranger's phone.
 *
 * Runs only when the encoder is already in place (push-model.sh), so it fetches
 * the four small assets rather than 620 MB:
 *
 *   ./scripts/push-model.sh && ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ModelDownloaderInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val downloader = ModelDownloader(context)

    // Never a named model: these tests stand up "the model that is not the
    // preferred one", and naming it meant that swapping PREFERRED turned the
    // fixture into the real model and deleted it mid-suite.
    private val fallback = SttModel.KNOWN.first { it !== SttModel.PREFERRED }

    @Test
    fun downloads_and_verifies_every_asset() {
        val encoder = File(downloader.modelDir(), SttModel.ENCODER)
        assumeTrue("run scripts/push-model.sh first", encoder.length() == encoderSize())

        // Force everything except the encoder to be re-fetched.
        SttModel.ASSETS.filter { it.filename != SttModel.ENCODER }
            .forEach { File(downloader.modelDir(), it.filename).delete() }
        assertTrue("setup should have made the model incomplete", !downloader.isPresent())

        val result = runBlocking { downloader.download { _, _, _ -> } }
        assertTrue("download failed: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertTrue("isPresent() false after a successful download", downloader.isPresent())

        // The hash is the point: sizes matching proves nothing about content.
        for (asset in SttModel.ASSETS) {
            val f = File(downloader.modelDir(), asset.filename)
            assertEquals("${asset.filename} size", asset.size, f.length())
            assertEquals("${asset.filename} sha256", asset.sha256, downloader.sha256(f))
        }
        assertTrue("a .part was left behind",
            downloader.modelDir().listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun removes_superseded_model_directories_once_the_new_one_is_complete() {
        assumeTrue("run scripts/push-model.sh first", downloader.isPresent())
        val stale = File(context.filesDir, "models/some-retired-model").apply { mkdirs() }
        File(stale, "encoder.onnx").writeBytes(ByteArray(1024))
        downloader.pruneSupersededModels()
        assertTrue("superseded model directory survived", !stale.exists())
        assertTrue("active model directory was removed", downloader.modelDir().exists())
    }

    /** The v0.7.0 outage: pruning before the replacement landed left no model. */
    @Test
    fun keeps_the_old_model_while_the_preferred_one_is_incomplete() {
        val encoder = File(downloader.modelDir(), SttModel.ENCODER)
        val restore = encoder.exists()
        if (restore) assertTrue(encoder.renameTo(File(downloader.modelDir(), "held.tmp")))
        try {
            val previous = File(context.filesDir, "models/${fallback.dir}")
                .apply { mkdirs() }
            File(previous, SttModel.ENCODER).writeBytes(ByteArray(1024))
            downloader.pruneSupersededModels()
            assertTrue("pruned the only model the phone could still load", previous.exists())
            previous.deleteRecursively()
        } finally {
            if (restore) {
                assertTrue(
                    "fixture not restored — later tests would see no model",
                    File(downloader.modelDir(), "held.tmp").renameTo(encoder),
                )
            }
        }
    }

    /**
     * The VAD ships inside each model's directory. Resolving its path once at
     * construction meant a phone running on a fallback model kept pointing into
     * a directory that pruning later deletes, and reloaded into degraded mode.
     */
    @Test
    fun the_vad_path_follows_whichever_model_is_current() {
        assumeTrue("run scripts/push-model.sh first", downloader.isPresent())
        val encoder = File(downloader.modelDir(), SttModel.ENCODER)
        val restore = encoder.exists()
        if (restore) assertTrue(encoder.renameTo(File(downloader.modelDir(), "held.tmp")))
        val previous = File(context.filesDir, "models/${fallback.dir}")
            .apply { mkdirs() }
        try {
            for (a in fallback.recognizerAssets) {
                RandomAccessFile(File(previous, a.filename), "rw").use { it.setLength(a.size) }
            }
            assertEquals(fallback, downloader.resolve())
            assertTrue(
                "VAD should resolve inside the model actually in use",
                downloader.vadModelPath().contains(fallback.dir),
            )
        } finally {
            previous.deleteRecursively()
            if (restore) {
                assertTrue(
                    "fixture not restored — later tests would see no model",
                    File(downloader.modelDir(), "held.tmp").renameTo(encoder),
                )
            }
        }
        // Preferred model whole again -> the path must move with it.
        assertEquals(SttModel.PREFERRED, downloader.resolve())
        assertTrue(downloader.vadModelPath().contains(SttModel.PREFERRED.dir))
    }

    private fun encoderSize() = SttModel.ASSETS.first { it.filename == SttModel.ENCODER }.size
}
