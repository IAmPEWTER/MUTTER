package com.peter.mutter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VadSegmenterInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The VAD lives inside whichever model directory is current, so its path
     * must be read on every load. Capturing it once meant a phone running on a
     * fallback model kept pointing into a directory that pruning later deletes,
     * and the post-download reload dropped silently into degraded RMS mode.
     */
    @Test
    fun resolves_its_model_path_on_every_load() {
        val real = File(ModelDownloader(context).vadModelPath())
        assumeTrue("run scripts/push-model.sh first", real.exists())

        var path = real.absolutePath
        val segmenter = VadSegmenter(modelPath = { path }, onChunk = {})
        try {
            assertTrue("should load from the real path", segmenter.load())
            segmenter.release()

            path = File(context.filesDir, "models/pruned-away/${SttModel.VAD}").absolutePath
            assertFalse("a path captured at construction would still load", segmenter.load())
            segmenter.release()

            path = real.absolutePath
            assertTrue("and follows the path back", segmenter.load())
        } finally {
            segmenter.release()
        }
    }
}
