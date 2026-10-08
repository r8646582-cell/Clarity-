package com.umair.purpose.ledger

import com.umair.purpose.chat.ToolCall
import com.umair.purpose.data.db.ActionLog
import kotlinx.serialization.json.Json

/** Phase 3 durable action journal: what to do when an action is asked for again on the same turn. */
object ActionJournalRules {
    const val MAX_ATTEMPTS = 3

    enum class Decision { RUN, SKIP_DONE, SKIP_EXHAUSTED }

    private val json = Json { encodeDefaults = false }

    fun encode(call: ToolCall): String = json.encodeToString(ToolCall.serializer(), call)

    fun decode(s: String): ToolCall? = runCatching { json.decodeFromString(ToolCall.serializer(), s) }.getOrNull()

    /** The same action with the same arguments on the same turn has the same key. */
    fun key(call: ToolCall): String = call.kind + ":" + Integer.toHexString(encode(call).hashCode())

    /** [existing] is the journal row for this turn and key, if any. A row that already succeeded is never run again. */
    fun decide(existing: ActionLog?): Decision = when {
        existing == null -> Decision.RUN
        existing.status == ActionLog.OK -> Decision.SKIP_DONE
        existing.attempts >= MAX_ATTEMPTS -> Decision.SKIP_EXHAUSTED
        else -> Decision.RUN
    }

    /** Entries worth replaying or auditing: started and never finished, or failed, with attempts left. */
    fun replayable(all: List<ActionLog>): List<ActionLog> =
        all.filter { it.status != ActionLog.OK && it.attempts < MAX_ATTEMPTS }
}
