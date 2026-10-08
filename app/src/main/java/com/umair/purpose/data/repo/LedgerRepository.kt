package com.umair.purpose.data.repo

import com.umair.purpose.data.db.Contradiction
import com.umair.purpose.data.db.Disagreement
import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.ledger.HisMessage
import com.umair.purpose.ledger.LedgerRules
import javax.inject.Inject
import javax.inject.Singleton

/** Phase 3: the disagreement ledger and the contradiction register. */
@Singleton
class LedgerRepository @Inject constructor(private val db: PurposeDatabase) {
    private val disagreements = db.disagreementDao()
    private val contradictions = db.contradictionDao()

    /**
     * Stores what a reflection proposed, after checking each item against what he really wrote in [sessionId].
     * Called inside the reflection's transaction. Anything not found word for word in his messages is dropped.
     */
    suspend fun applyProposed(
        sessionId: Long,
        disagreementsIn: List<LedgerRules.ProposedDisagreement>,
        contradictionsIn: List<LedgerRules.ProposedContradiction>,
        now: Long,
    ) {
        if (disagreementsIn.isEmpty() && contradictionsIn.isEmpty()) return
        val his = db.messageDao().forSession(sessionId).filter { it.role == Message.ROLE_USER }
            .map { HisMessage(sessionId, it.createdAt, it.content) }
        // A contradiction can pair today's words with something he said in an earlier conversation.
        val earlier = if (contradictionsIn.isEmpty()) emptyList() else db.messageDao().userMessagesUntil(now)
            .map { HisMessage(it.sessionId, it.createdAt, it.content) }
        val knownD = disagreements.all().toMutableList()
        for (p in disagreementsIn) {
            LedgerRules.disagreement(p, his, sessionId, now, knownD)?.let { disagreements.insert(listOf(it)); knownD += it }
        }
        val knownC = contradictions.all().toMutableList()
        for (p in contradictionsIn) {
            LedgerRules.contradiction(p, (earlier + his).distinct(), now, knownC)?.let { contradictions.insert(listOf(it)); knownC += it }
        }
    }

    suspend fun lines(date: (Long) -> String): List<String> = LedgerRules.lines(disagreements.open(), contradictions.open(), date)

    suspend fun resolveDisagreement(id: Long, now: Long) = disagreements.resolve(id, now)

    suspend fun explainContradiction(id: Long, explanation: String?, now: Long) =
        contradictions.explain(id, explanation?.trim()?.takeIf { it.isNotEmpty() }, now)
}
