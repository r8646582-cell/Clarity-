package com.umair.purpose.dev

import android.content.Context
import java.io.File
import java.time.Instant

/**
 * Keeps the last crash on this phone so Umair can copy it from Settings > Advanced > Developer.
 * Only exception types and stack frames are written: exception messages are left out, because they can
 * carry text from his conversations. Nothing leaves the phone.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context, version: String) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file(app).writeText(format(error, thread.name, version, System.currentTimeMillis())) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    /** Types and frames only, cause chain included, never [Throwable.message]. */
    fun format(error: Throwable, thread: String, version: String, at: Long): String = buildString {
        append("Purpose ").append(version).append('\n')
        append("Time: ").append(Instant.ofEpochMilli(at)).append('\n')
        append("Thread: ").append(thread).append('\n')
        var t: Throwable? = error
        var depth = 0
        val seen = HashSet<Throwable>()
        while (t != null && depth < 8 && seen.add(t)) {
            append(if (depth == 0) "" else "Caused by: ").append(t.javaClass.name).append('\n')
            t.stackTrace.take(40).forEach { append("    at ").append(it.toString()).append('\n') }
            t = t.cause
            depth++
        }
    }
}
