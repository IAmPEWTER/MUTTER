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
    fun removes_model_directories_that_are_no_longer_active() {
        val stale = File(context.filesDir, "models/some-retired-model").apply { mkdirs() }
        File(stale, "encoder.onnx").writeBytes(ByteArray(1024))
        downloader.pruneOtherModels()
        assertTrue("stale model directory survived", !stale.exists())
        assertTrue("active model directory was removed", downloader.modelDir().exists())
    }

    private fun encoderSize() = SttModel.ASSETS.first { it.filename == SttModel.ENCODER }.size
}
