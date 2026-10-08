package com.umair.purpose.growth

import com.umair.purpose.data.db.Milestone

/**
 * Developer → "Preview tree with sample leaves": a made-up tree of 10, 50 or 300 leaves (never saved), so the
 * drawing can be judged at every size. Deterministic, so the same count always looks the same.
 */
object SampleTree {
    val VALUES = listOf("family", "faith", "growth", "honesty", "health")

    private val BRANCHES = mapOf(
        "studies_career" to listOf("FAR", "Articleship", "Audit"),
        "relationships" to listOf("Abbu", "Ammi", "Friends", "Sister"),
        "habits" to listOf("Phone", "Sleep", "Mornings"),
        "health" to listOf("Running", "Food"),
        "mindset" to listOf("Self-talk"),
        "eq" to listOf("Anger", "Worry"),
        "character" to listOf("Honesty"),
        "money" to listOf("Saving"),
        "meaning" to listOf("Faith"),
        "rest_joy" to listOf("Cricket", "Mountains"),
    )

    /** [count] leaves spread the way a real life might: most on studies, relationships and habits. */
    fun leaves(count: Int, now: Long = 1_790_000_000_000L): List<TreeLeaf> {
        val weights = listOf(
            "studies_career" to 6, "relationships" to 5, "habits" to 5, "health" to 3, "mindset" to 3,
            "eq" to 3, "character" to 2, "rest_joy" to 2, "meaning" to 1, "money" to 1,
        )
        val r = TreeLayout.rng("sample:$count")
        val total = weights.sumOf { it.second }
        return (1..count).map { i ->
            var pick = r.nextInt(total)
            val area = weights.first { (_, w) -> (pick < w).also { pick -= w } }.first
            val subs = BRANCHES[area].orEmpty()
            // A third sit on the area branch itself.
            val branch = if (subs.isEmpty() || r.nextInt(3) == 0) null else subs[r.nextInt(subs.size)]
            TreeLeaf(
                id = -i.toLong(),
                type = Milestone.TYPES[r.nextInt(Milestone.TYPES.size)],
                area = area,
                branch = branch,
                decidedAt = now - (count - i) * 3L * 24 * 60 * 60 * 1000,
                bright = i == count,
            )
        }
    }

    fun input(count: Int): TreeInput {
        val leaves = leaves(count)
        return TreeInput(
            values = VALUES,
            chapters = (count / 12).coerceAtMost(40),
            ageMonths = (count / 4).coerceAtMost(120),
            leaves = leaves,
            branchOrder = BRANCHES,
        )
    }
}
