package com.peter.mutter

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The capture path is now reused across holds instead of rebuilt per hold, and
 * it prefers VOICE_RECOGNITION over MIC. Both are things that can fail on a
 * specific device and cannot fail on the JVM, so they are checked here.
 *
 * Note this cannot cover the bug that motivated the rewrite: Android silences a
 * *background* app's capture, and an instrumentation process is foreground. Nor
 * can the volume-down path be driven — injected key events bypass accessibility
 * key filtering. On a real device, `adb logcat -s MutterAudio` prints how long
 * the first non-silent window took and whether the framework silenced us.
 */
@RunWith(AndroidJUnit4::class)
class AudioRecorderInstrumentedTest {

    // The task reinstalls the app, which drops the runtime grant; without this
    // the tests fail for a reason that has nothing to do with the code.
    @get:Rule
    val micPermission: GrantPermissionRule =
        GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test
    fun delivers_windows_of_the_expected_size() {
        val recorder = AudioRecorder()
        assertTrue("prepare() found no usable audio source", recorder.prepare())
        try {
            val windows = AtomicInteger()
            val sized = AtomicInteger()
            val latch = CountDownLatch(10)
            assertTrue("start() failed", recorder.start { w ->
                windows.incrementAndGet()
                if (w.size == 512) sized.incrementAndGet()
                latch.countDown()
            })
            assertTrue("no audio windows within 5s", latch.await(5, TimeUnit.SECONDS))
            recorder.stop()
            assertEquals("every window should be one VAD frame",
                windows.get(), sized.get())
        } finally {
            recorder.release()
        }
    }

    @Test
    fun survives_being_reused_across_holds() {
        val recorder = AudioRecorder()
        try {
            repeat(3) { hold ->
                val latch = CountDownLatch(3)
                assertTrue("hold $hold failed to start", recorder.start { latch.countDown() })
                assertTrue("hold $hold produced no audio", latch.await(5, TimeUnit.SECONDS))
                recorder.stop()
            }
        } finally {
            recorder.release()
        }
    }

    @Test
    fun stop_without_start_is_harmless() {
        val recorder = AudioRecorder()
        recorder.stop()
        recorder.release()
        recorder.stop()
    }
}
