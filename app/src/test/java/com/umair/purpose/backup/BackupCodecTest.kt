package com.umair.purpose.backup

import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BackupCodecTest {
    private val data = BackupData(
        exportedAt = 1, sessions = listOf(Session(1, 0, 5, "summary", true)),
        messages = listOf(Message(1, 1, "user", "Salam, kya haal hai? 🙂", 0)),
        profile = emptyList(), people = emptyList(),
        notes = listOf(Note(1, "pattern", "x", "guess", Note.ACTIVE, 1, 0, 0)),
        promises = emptyList(), areas = emptyList(), reports = emptyList(), usage = emptyList(),
    )
    private val pass = "correct horse".toCharArray()

    @Test
    fun `round trip`() {
        val file = BackupCodec.encode(data, pass, iterations = 10_000)
        assertEquals(data, BackupCodec.decode(file, pass))
        assertFalse(String(file, Charsets.ISO_8859_1).contains("Salam"))
    }

    @Test(expected = BadPassphraseException::class)
    fun `wrong passphrase`() {
        BackupCodec.decode(BackupCodec.encode(data, pass, 10_000), "wrong horse!".toCharArray())
    }

    @Test(expected = BadPassphraseException::class)
    fun `tampered file`() {
        val file = BackupCodec.encode(data, pass, 10_000)
        file[file.size - 1] = (file[file.size - 1].toInt() xor 1).toByte()
        BackupCodec.decode(file, pass)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `not a backup`() {
        BackupCodec.decode("hello world, definitely not a backup file".toByteArray(), pass)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `short passphrase rejected`() {
        BackupCodec.encode(data, "short".toCharArray(), 10_000)
    }

    @Test
    fun `default is the current OWASP count and is stored in the header`() {
        assertEquals(600_000, BackupCodec.DEFAULT_ITERATIONS)
        val file = BackupCodec.encode(data, pass)
        // "PURPOSE1" (8) + salt (16), then the 4-byte iteration count.
        assertEquals(600_000, java.nio.ByteBuffer.wrap(file).getInt(24))
        assertEquals(data, BackupCodec.decode(file, pass))
    }

    @Test
    fun `a backup written with the old 210k count still restores`() {
        val old = BackupCodec.encode(data, pass, BackupCodec.LEGACY_ITERATIONS)
        assertEquals(210_000, java.nio.ByteBuffer.wrap(old).getInt(24))
        assertEquals(data, BackupCodec.decode(old, pass))
    }
}
