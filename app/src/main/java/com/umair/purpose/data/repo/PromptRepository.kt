package com.umair.purpose.data.repo

import android.content.Context
import com.umair.purpose.chat.ChatPrefixCache
import com.umair.purpose.data.db.PromptOverride
import com.umair.purpose.data.db.PurposeDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads Umair's prompt files from assets/prompts, unchanged, unless he saved his own version in
 * Developer > Prompt editor: then that version (stored in the database) is used instead.
 */
@Singleton
class PromptRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: PurposeDatabase,
    private val prefixCache: ChatPrefixCache,
) {
    private val builtIn = ConcurrentHashMap<String, String>()
    @Volatile private var overrides: Map<String, String>? = null
    private var generation = 0L

    suspend fun persona(): String = load("persona.md")

    /** The chat system prompt: persona, then the voice examples right after it (CLAUDE.md Core flow 1, 1b). */
    suspend fun chatSystemPrompt(): String = systemPrompt(persona(), load("examples.md"))

    suspend fun load(name: String): String = overrides()[name] ?: builtIn(name)

    suspend fun builtIn(name: String): String = builtIn[name] ?: withContext(Dispatchers.IO) {
        context.assets.open("prompts/$name").bufferedReader().use { it.readText() }
    }.also { builtIn[name] = it }

    /** Every prompt file in the app, by name. */
    suspend fun names(): List<String> = withContext(Dispatchers.IO) {
        context.assets.list("prompts").orEmpty().filter { it.endsWith(".md") }.sorted()
    }

    fun observeOverrides(): Flow<List<PromptOverride>> = db.promptDao().observeAll()

    suspend fun isEdited(name: String): Boolean = name in overrides()

    suspend fun editedNames(): Set<String> = overrides().keys

    suspend fun save(name: String, text: String) {
        require(name in names()) { "Unknown prompt file" }
        val original = builtIn(name)
        val problems = com.umair.purpose.prompt.PromptValidation.errors(name, text, original)
        require(problems.isEmpty()) { problems.joinToString("\n") }
        db.promptDao().upsert(PromptOverride(name, text, System.currentTimeMillis(), builtInHash = hash(original)))
        changed()
    }

    /**
     * UPDATE-13/14: his edited prompts whose built-in version changed in an app update since he saved his (or that
     * were saved before this was tracked). He decides which to keep: his, or Reset to built-in.
     */
    suspend fun builtInChangedSinceEdit(): Set<String> =
        db.promptDao().all().filter { o -> o.builtInHash == null || o.builtInHash != hash(builtIn(o.name)) }.map { it.name }.toSet()

    suspend fun reset(name: String) {
        db.promptDao().delete(name)
        changed()
    }

    /** "Export all prompts": every file as it's used now, in one text. */
    suspend fun exportAll(): String = buildString {
        append("Purpose prompts, exported ").append(java.time.LocalDate.now()).append("\n\n")
        names().forEach { n ->
            append("===== ").append(n).append(if (isEdited(n)) " (edited)" else " (built-in)").append(" =====\n")
            append(load(n).trimEnd()).append("\n\n")
        }
    }

    private suspend fun overrides(): Map<String, String> {
        while (true) {
            val started = synchronized(this) {
                overrides?.let { return it }
                generation
            }
            val loaded = db.promptDao().all().associate { it.name to it.text }
            synchronized(this) {
                if (generation == started) {
                    overrides = loaded
                    return loaded
                }
            }
        }
    }

    /** Restore and erase write prompt rows directly, so they must invalidate every derived cache too. */
    fun changed() {
        synchronized(this) {
            generation++
            overrides = null
        }
        // The next chat request rebuilds its prefix with the new text.
        prefixCache.clear()
        onChange.forEach { it() }
    }

    /** Things that cache parsed prompts (the journey catalog) drop their copy when a prompt changes. */
    private val onChange = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addOnChange(listener: () -> Unit) {
        onChange += listener
    }

    companion object {
        /** A short fingerprint of a prompt's text. */
        fun hash(text: String): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        fun systemPrompt(persona: String, examples: String): String = persona.trimEnd() + "\n\n" + examples.trim() + "\n"
    }
}
