package com.umair.purpose.ledger

import com.umair.purpose.data.db.Contradiction
import com.umair.purpose.data.db.Disagreement
import com.umair.purpose.memory.QuoteCheck

/** One of his own messages, for finding when he said something. */
data class HisMessage(val sessionId: Long, val createdAt: Long, val text: String)

/**
 * Phase 3 rules for the disagreement ledger and the contradiction register. The model proposes; this decides.
 * Nothing is stored unless it is his own words, found word for word in something he wrote. A claim the coach
 * made is stored as the coach's claim, labelled, and never counts as evidence about him.
 */
object LedgerRules {
    /** Most open items sent to the model, so the context block stays small. */
    const val MAX_SHOWN = 4

    data class ProposedDisagreement(val claim: String = "", val hisPosition: String = "")
    data class ProposedContradiction(val quoteA: String = "", val quoteB: String = "")

    private fun norm(s: String) = s.lowercase().replace('’', '\'').replace('‘', '\'')
        .replace('“', '"').replace('”', '"').replace(Regex("\\s+"), " ").trim()

    private fun find(quote: String, his: List<HisMessage>): HisMessage? =
        his.filter { QuoteCheck.isHis(quote, listOf(it.text)) }.minByOrNull { it.createdAt }

    /** A disagreement needs the coach's claim and his position, and his position must really be his. */
    fun disagreement(p: ProposedDisagreement, his: List<HisMessage>, sessionId: Long, now: Long, existing: List<Disagreement>): Disagreement? {
        val claim = p.claim.trim()
        val position = p.hisPosition.trim()
        if (claim.isEmpty() || claim.length > 400) return null
        val msg = find(position, his) ?: return null
        if (existing.any { !it.resolved && norm(it.claim) == norm(claim) }) return null
        return Disagreement(sessionId = sessionId, claim = claim, hisPosition = position, raisedAt = msg.createdAt.coerceAtMost(now))
    }

    /**
     * A contradiction is two different statements of his, both found word for word, in two different messages.
     * The same pair (either order) is never registered twice.
     */
    fun contradiction(p: ProposedContradiction, his: List<HisMessage>, now: Long, existing: List<Contradiction>): Contradiction? {
        val a = find(p.quoteA, his) ?: return null
        val b = find(p.quoteB, his) ?: return null
        if (norm(p.quoteA) == norm(p.quoteB) || (a === b)) return null
        val (first, second) = if (a.createdAt <= b.createdAt) (p.quoteA to a) to (p.quoteB to b) else (p.quoteB to b) to (p.quoteA to a)
        val qa = first.first.trim()
        val qb = second.first.trim()
        if (existing.any { norm(it.quoteA) == norm(qa) && norm(it.quoteB) == norm(qb) }) return null
        return Contradiction(
            sessionId = second.second.sessionId, sessionIdA = first.second.sessionId, quoteA = qa, quoteB = qb,
            statedAtA = first.second.createdAt, statedAtB = second.second.createdAt, createdAt = now,
        )
    }

    /**
     * Plain lines for the context block. [date] formats an epoch as a day. Open items only, newest first, capped.
     * Both sides of a contradiction are shown with when he said them, in his words.
     */
    fun lines(disagreements: List<Disagreement>, contradictions: List<Contradiction>, date: (Long) -> String): List<String> {
        val out = mutableListOf<String>()
        val d = disagreements.filter { !it.resolved }.sortedByDescending { it.raisedAt }
        if (d.isNotEmpty()) {
            out += "Open disagreements (the coach's view against his; his position is quoted):"
            d.take(MAX_SHOWN).forEach { out += "- ${date(it.raisedAt)}: coach said \"${it.claim}\"; he said \"${it.hisPosition}\"" }
        }
        val c = contradictions.filter { it.status == Contradiction.OPEN }.sortedByDescending { it.statedAtB }
        if (c.isNotEmpty()) {
            out += "Open contradictions in his own words (both quoted; competing values are not hypocrisy):"
            c.take(MAX_SHOWN).forEach { out += "- ${date(it.statedAtA)} \"${it.quoteA}\" versus ${date(it.statedAtB)} \"${it.quoteB}\"" }
        }
        return out
    }
}
