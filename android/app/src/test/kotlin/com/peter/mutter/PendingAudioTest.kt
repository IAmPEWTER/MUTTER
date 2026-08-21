package com.peter.mutter

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingAudioTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun files(vararg agesInDays: Double): List<File> =
        agesInDays.mapIndexed { i, age ->
            File.createTempFile("pending$i", ".wav").apply {
                deleteOnExit()
                setLastModified(now - (age * day).toLong())
            }
        }

    @Test
    fun `keeps everything when under both limits`() {
        assertTrue(PendingAudio.selectForDeletion(files(0.0, 1.0, 2.0), now).isEmpty())
    }

    @Test
    fun `drops the oldest past the count cap`() {
        val f = files(0.0, 1.0, 2.0, 3.0)
        val gone = PendingAudio.selectForDeletion(f, now, keep = 2)
        assertEquals(listOf(f[2], f[3]), gone)
    }

    @Test
    fun `drops anything past the age cap even inside the count cap`() {
        val f = files(0.0, 20.0)
        assertEquals(listOf(f[1]), PendingAudio.selectForDeletion(f, now, keep = 10))
    }

    @Test
    fun `an empty directory is not an error`() {
        assertTrue(PendingAudio.selectForDeletion(emptyList(), now).isEmpty())
    }
}
