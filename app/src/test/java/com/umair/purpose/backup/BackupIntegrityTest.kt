package com.umair.purpose.backup

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BackupIntegrityTest {
    @Test fun `complete backup round trips without loading a second full copy`() {
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        BackupIntegrity.verify(bytes, ByteArrayInputStream(bytes))
    }

    @Test fun `truncation and corruption cannot report backup success`() {
        val bytes = ByteArray(100) { it.toByte() }
        for (actual in listOf(bytes.copyOf(99), bytes.copyOf().also { it[50] = 0 }, bytes + byteArrayOf(0))) {
            try { BackupIntegrity.verify(bytes, ByteArrayInputStream(actual)); fail("Must reject invalid persisted bytes") }
            catch (_: IllegalStateException) { }
        }
    }

    @Test fun `read failure is propagated and the provider stream is closed`() {
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("Provider disconnected")
            override fun close() { closed = true }
        }
        try { BackupIntegrity.verify(byteArrayOf(1), input); fail("Must report read failure") }
        catch (_: IOException) { }
        assertTrue(closed)
    }
}
