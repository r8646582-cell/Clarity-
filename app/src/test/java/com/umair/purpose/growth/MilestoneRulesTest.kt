package com.umair.purpose.growth

import com.umair.purpose.data.db.Branch
import com.umair.purpose.data.db.Milestone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MilestoneRulesTest {
    private val zone = ZoneId.of("Asia/Karachi")
    private val now = LocalDate.of(2026, 11, 29).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
    private val habitStats = EvidenceStats(
        LocalDate.of(2026, 11, 29),
        listOf(ActionStats("study_5pm", "Study", LocalDate.of(2026, 11, 1), 10, 9, 24, 19, 24, 19, true, 29)),
        emptyList(), 20, 30, 12, emptyList(), emptyList(),
    )
    private val noStats = habitStats.copy(actions = emptyList())

    private fun proposal(title: String, type: String = "habit_built", confidence: String = "high", branch: String? = "far") =
        MilestoneResult.Proposal(type, "studies_career", branch, title, "You study at 5pm now.", listOf("19 of 24 days", "On 26 Nov you said: 'it's just what I do now'"), confidence)

    @Test
    fun `parses the prompt's shape and tolerates text around it`() {
        val r = MilestoneRules.parse("Here:\n{\"proposals\": [{\"type\": \"habit_built\", \"area\": \"studies_career\", \"title\": \"T\", \"description\": \"D\", \"evidence\": [\"e\"], \"confidence\": \"high\"}], \"considered_but_not_yet\": [{\"title\": \"X\", \"missing\": \"9 more days\"}]}")
        assertEquals(1, r.proposals.size)
        assertEquals("9 more days", r.consideredButNotYet.single().missing)
    }

    @Test
    fun `only high confidence, real types and real evidence`() {
        val r = MilestoneResult(listOf(proposal("Studying at 5pm became a habit", confidence = "medium"), proposal("x", type = "weight_loss")))
        assertTrue(MilestoneRules.accept(r, emptyList(), emptyList(), habitStats, now, zone).isEmpty())
        // A habit leaf without the computed minimum never gets through, however sure the model is.
        val r2 = MilestoneResult(listOf(proposal("Studying at 5pm became a habit")))
        assertTrue(MilestoneRules.accept(r2, emptyList(), emptyList(), noStats, now, zone).isEmpty())
        val ok = MilestoneRules.accept(r2, emptyList(), emptyList(), habitStats, now, zone).single()
        assertEquals(Milestone.PROPOSED, ok.status)
        assertEquals("it's just what I do now", ok.quote)
        assertEquals(listOf("19 of 24 days", "On 26 Nov you said: 'it's just what I do now'"), MilestoneRules.decodeEvidence(ok.evidenceJson))
    }

    @Test
    fun `never re-proposed, two a run, three a month`() {
        val declined = Milestone(type = "habit_built", area = "studies_career", title = "Studying at 5pm became a habit", description = "", confidence = "high", proposedAt = 0, status = Milestone.DECLINED)
        val r = MilestoneResult(listOf(proposal("Studying at 5pm became a habit!")))
        assertTrue(MilestoneRules.accept(r, listOf(declined), emptyList(), habitStats, now, zone).isEmpty())
        assertTrue(MilestoneRules.accept(r, listOf(declined.copy(status = Milestone.REMOVED)), emptyList(), habitStats, now, zone).isEmpty())
        assertTrue(MilestoneRules.accept(r, listOf(declined.copy(status = Milestone.SNOOZED, snoozeUntil = now + 1)), emptyList(), habitStats, now, zone).isEmpty())

        val three = MilestoneResult(listOf(proposal("A habit one"), proposal("Another quite different"), proposal("Third thing entirely")))
        assertEquals(2, MilestoneRules.accept(three, emptyList(), emptyList(), habitStats, now, zone).size)
        val twoThisMonth = List(2) { declined.copy(title = "old $it", status = Milestone.ACCEPTED, proposedAt = now - 1000) }
        assertEquals(1, MilestoneRules.accept(three, twoThisMonth, emptyList(), habitStats, now, zone).size)
    }

    @Test
    fun `branches reuse his exact names`() {
        val branches = listOf(Branch(1, "studies_career", "FAR", 0))
        assertEquals("FAR", MilestoneRules.branchName("far", "studies_career", branches))
        assertEquals("Abbu", MilestoneRules.branchName("Abbu", "relationships", branches))
        assertNull(MilestoneRules.branchName("studies career", "studies_career", branches))
        assertNull(MilestoneRules.branchName("null", "studies_career", branches))
    }
}
