package com.umair.purpose.backup

import java.io.InputStream
import java.security.MessageDigest

/** A provider must return the complete bytes before a backup can replace older recovery copies. */
object BackupIntegrity {
    fun verify(expected: ByteArray, input: InputStream) {
        val actual = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        var count = 0L
        input.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                actual.update(buffer, 0, read)
                count += read
            }
        }
        val wanted = MessageDigest.getInstance("SHA-256").digest(expected)
        check(count == expected.size.toLong() && MessageDigest.isEqual(wanted, actual.digest())) {
            "Backup verification failed; the recovery file was not stored completely"
        }
    }
}
