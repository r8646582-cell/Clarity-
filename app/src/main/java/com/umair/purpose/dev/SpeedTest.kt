package com.umair.purpose.dev

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import com.umair.purpose.data.db.BehaviorEvent
import com.umair.purpose.data.db.Letter
import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Migrations
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.Quote
import com.umair.purpose.data.db.Session
import com.umair.purpose.memory.SearchDocs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.measureTimeMillis

/**
 * UPDATE-15 "Speed with years of data": Developer → "Generate 5 years of fake data" builds a separate, throwaway
 * database (never his real one) with five years of made-up conversations and memory, times what the screens
 * and each chat message read, then deletes it. Nothing touches his data.
 */
@Singleton
class SpeedTest @Inject constructor(@ApplicationContext private val context: Context) {
    data class Result(val lines: List<String>)

    suspend fun run(onProgress: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val name = "purpose-speedtest.db"
        context.deleteDatabase(name)
        System.loadLibrary("sqlcipher")
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val db = Room.databaseBuilder(context, PurposeDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(key))
            .addMigrations(*Migrations.ALL)
            .build()
        val lines = mutableListOf<String>()
        try {
            val zone = ZoneId.systemDefault()
            val day = 24L * 60 * 60 * 1000
            val start = System.currentTimeMillis() - 5 * 365 * day
            val days = 5 * 365
            onProgress("Writing five years of made-up conversations…")
            val write = measureTimeMillis {
                // About one conversation a day, 24 messages each: ~44,000 messages.
                for (chunk in (0 until days).chunked(60)) {
                    db.withTransaction {
                        for (d in chunk) {
                            val at = start + d * day + 21 * 60 * 60 * 1000L
                            val sid = db.sessionDao().insert(
                                Session(startedAt = at, endedAt = at + 40 * 60_000, reflected = true, userMessageCount = 12,
                                    summary = "Made-up day $d: studying FAR, a talk with Abbu, sleep and the phone.", title = "Day $d", significance = 1 + d % 5)
                            )
                            for (m in 0 until 24) {
                                db.messageDao().insert(Message(sessionId = sid, role = if (m % 2 == 0) Message.ROLE_USER else Message.ROLE_ASSISTANT,
                                    content = "Message $m of day $d. " + "Some ordinary words about the day. ".repeat(4), createdAt = at + m * 60_000L))
                            }
                            db.quoteDao().insert(listOf(Quote(sessionId = sid, text = "Something I said on day $d", createdAt = at)))
                            db.behaviorDao().insert(listOf(BehaviorEvent(sessionId = sid, createdAt = at, situation = "studying felt heavy", action = "scrolled", outcome = "lost the evening")))
                            db.promiseDao().insert(Promise(text = "Study at 5pm, day $d", createdAt = at, dueAt = null, status = if (d % 4 == 0) Promise.BROKEN else Promise.KEPT, sourceSessionId = sid, resolvedAt = at + day))
                            if (d % 7 == 6) db.letterDao().insert(Letter(kind = Letter.WEEKLY, periodStart = "x", periodEnd = "x$d", createdAt = at, title = "Week $d", content = "A made-up letter. ".repeat(80)))
                        }
                    }
                    onProgress("Writing… ${chunk.last() * 100 / days}%")
                }
                db.noteDao().upsert((1..400).map { Note(type = Note.TYPES[it % 4], text = "Made-up note $it", confidence = "likely", status = if (it % 5 == 0) Note.ACTIVE else Note.RETIRED, timesSeen = it % 9 + 1, firstSeen = start, lastSeen = start + it * day) })
            }
            lines += "Wrote 5 years of made-up data (${days} conversations, ${days * 24} messages) in ${write / 1000}s."
            onProgress("Indexing the archive…")
            val index = measureTimeMillis {
                db.withTransaction {
                    val docs = db.sessionDao().all().mapNotNull { SearchDocs.summary(it, zone) } + db.quoteDao().all().map { SearchDocs.quote(it, zone) }
                    docs.chunked(500).forEach { db.searchDao().insert(it) }
                }
            }
            lines += "Built the search index in ${index}ms."
            onProgress("Timing the screens…")
            fun time(label: String, block: suspend () -> Int) {
                var n = 0
                val ms = measureTimeMillis { kotlinx.coroutines.runBlocking { n = block() } }
                lines += "$label: ${ms}ms ($n rows)"
            }
            time("Conversations drawer (first page)") { db.sessionDao().pageConversations(50, 0).size }
            time("A long conversation (latest 50 messages)") { db.messageDao().latestPage(db.sessionDao().openSessionOrLatestId() ?: 1L, 50).size }
            time("Mirror (first page of letters)") { db.letterDao().page(30, 0).size }
            time("Past promises (first page)") { db.promiseDao().pastPage(30, 0).size }
            time("Archive search for one message") { db.searchDao().search("abbu* OR far* OR sleep*", 60).size }
            time("Memory for one chat message") { db.noteDao().all().size + db.behaviorDao().recent(50).size + db.quoteDao().recent(200).size }
            lines += "All reads above are what a screen or a chat message does; under ~100ms each means no visible wait."
        } finally {
            db.close()
            context.deleteDatabase(name)
            lines += "The test database was deleted. Your own data was never touched."
        }
        Result(lines)
    }
}
