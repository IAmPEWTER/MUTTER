package com.peter.mutter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The one thing JVM unit tests cannot cover: that the model actually loads and
 * decodes through sherpa-onnx's Android JNI on an arm64 device.
 *
 * Skipped unless the model is already in filesDir — it is ~620 MB, so this is
 * a deliberate on-demand check, not something every build pays for:
 *
 *   scripts/push-model.sh && ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SttEngineInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun transcribes_the_canonical_clip() {
        val downloader = ModelDownloader(context)
        assumeTrue("model not on device — run scripts/push-model.sh", downloader.isPresent())

        val engine = SttEngine(downloader)
        assertTrue("engine failed to load", engine.load())
        try {
            val samples = readWav(
                InstrumentationRegistry.getInstrumentation().context.assets.open("canonical.wav")
            )
            val text = engine.transcribe(samples, 16_000)
            assertNotNull("transcribe returned null (engine failure)", text)
            // Sherpa's own fixture for this model. Substring, not equality:
            // this asserts the pipeline is wired up, not the model's WER.
            assertTrue("unexpected transcript: $text", text!!.contains("old portrait"))
        } finally {
            engine.release()
        }
    }

    private fun readWav(stream: InputStream): FloatArray = stream.use { input ->
        val bytes = input.readBytes()
        // 16 kHz mono PCM16, canonical 44-byte header.
        val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN)
        FloatArray((bytes.size - 44) / 2) { pcm.short / 32768f }
    }
}
