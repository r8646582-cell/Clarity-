package com.umair.purpose.backup

import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Session
import org.junit.Assert.*
import org.junit.Test

class BackupValidationTest {
    private val empty = BackupData(exportedAt = 1, sessions = emptyList(), messages = emptyList(), profile = emptyList(),
        people = emptyList(), notes = emptyList(), promises = emptyList(), areas = emptyList(), usage = emptyList())
    private val session = Session(id = 1, startedAt = 1)

    @Test fun `empty and historical backup versions are accepted`() {
        for (version in 1..BackupData.FORMAT) BackupValidation.check(empty.copy(formatVersion = version))
    }
    @Test(expected = IllegalArgumentException::class) fun `future format is refused`() { BackupValidation.check(empty.copy(formatVersion = 4)) }
    @Test(expected = IllegalArgumentException::class) fun `duplicate conversation ids are refused`() {
        BackupValidation.check(empty.copy(sessions = listOf(session, session)))
    }
    @Test(expected = IllegalArgumentException::class) fun `off record identities cannot enter saved backup`() {
        BackupValidation.check(empty.copy(sessions = listOf(session.copy(id = -1))))
    }
    @Test(expected = IllegalArgumentException::class) fun `orphan messages are refused`() {
        BackupValidation.check(empty.copy(messages = listOf(Message(id = 1, sessionId = 9, role = "user", content = "x", createdAt = 1))))
    }
    @Test(expected = IllegalArgumentException::class) fun `multiple open conversations are refused`() {
        BackupValidation.check(empty.copy(sessions = listOf(session, session.copy(id = 2))))
    }
    @Test fun `bounded reads permit exact limit`() {
        assertArrayEquals(byteArrayOf(1, 2, 3), BackupCodec.readBounded(byteArrayOf(1, 2, 3).inputStream(), 3))
    }
    @Test(expected = IllegalArgumentException::class) fun `bounded reads stop oversized input`() {
        BackupCodec.readBounded(byteArrayOf(1, 2, 3, 4).inputStream(), 3)
    }
    @Test(expected = IllegalArgumentException::class) fun `future encrypted format is refused after decoding`() {
        val pass = "strong passphrase".toCharArray()
        BackupCodec.decode(BackupCodec.encode(empty.copy(formatVersion = 4), pass, 10_000), pass)
    }
    @Test(expected = IllegalArgumentException::class) fun `encode cannot create unreadable iteration count`() {
        BackupCodec.encode(empty, "strong passphrase".toCharArray(), 1)
    }
}
