package com.umair.purpose.dev

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
    @Test
    fun keepsTypesAndFramesButNeverMessages() {
        val error = IllegalStateException("he said: I feel worthless", RuntimeException("secret key sk-123"))
        val text = CrashLog.format(error, "main", "0.3.0", 0L)
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("Caused by: java.lang.RuntimeException"))
        assertTrue(text.contains("    at "))
        assertFalse(text.contains("worthless"))
        assertFalse(text.contains("sk-123"))
    }
}
