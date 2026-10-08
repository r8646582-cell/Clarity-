package com.umair.purpose.growth

import com.umair.purpose.data.db.Branch
import com.umair.purpose.data.db.Milestone
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.memory.OnboardingFormat
import com.umair.purpose.memory.OnboardingSteps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the tree screen and the Path preview draw from, read live. */
data class TreeData(val input: TreeInput, val leaves: List<Milestone>)

@Singleton
class TreeRepository @Inject constructor(private val db: PurposeDatabase) {
    /** The tree as it is now: his values, chapters, age, accepted leaves and their branches. */
    fun observe(zone: ZoneId = ZoneId.systemDefault()): Flow<TreeData> = combine(
        db.milestoneDao().observeLeaves(),
        db.branchDao().observeAll(),
        db.chapterDao().observeAll(),
        db.onboardingDao().observeAll(),
        db.snapshotDao().observeLatest(),
    ) { leaves, branches, chapters, steps, snapshot ->
        val values = OnboardingFormat.decodeValues(steps.firstOrNull { it.step == OnboardingSteps.VALUES_SORT }?.data)
            .ifEmpty { OnboardingFormat.decodeValues(snapshot?.valuesJson) }
        val first = db.sessionDao().firstStartedAt()
        TreeData(input(values, chapters.size, first, leaves, branches, System.currentTimeMillis(), zone), leaves)
    }

    companion object {
        private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

        fun input(values: List<String>, chapters: Int, firstSession: Long?, leaves: List<Milestone>, branches: List<Branch>, now: Long, zone: ZoneId): TreeInput {
            val newest = leaves.maxByOrNull { it.decidedAt ?: it.proposedAt }
            return TreeInput(
                values = values,
                chapters = chapters,
                ageMonths = firstSession?.let {
                    ChronoUnit.MONTHS.between(Instant.ofEpochMilli(it).atZone(zone), Instant.ofEpochMilli(now).atZone(zone)).toInt().coerceAtLeast(0)
                } ?: 0,
                leaves = leaves.map { m ->
                    TreeLeaf(
                        id = m.id, type = m.type, area = m.area, branch = m.branch, decidedAt = m.decidedAt ?: m.proposedAt,
                        // The newest leaf is slightly brighter for a week.
                        bright = m.id == newest?.id && now - (m.decidedAt ?: 0L) < WEEK_MS,
                    )
                },
                branchOrder = branches.sortedBy { it.createdAt }.groupBy({ it.area }, { it.name }),
            )
        }

        /**
         * The growing animation's starting point: for each stroke the new leaf hangs on, how much of it existed
         * before (computed from the tree without that leaf; 0 for a branch it brings).
         */
        fun growFrom(input: TreeInput, leafId: Long): Map<String, Float> {
            val leaf = input.leaves.firstOrNull { it.id == leafId } ?: return emptyMap()
            val after = TreeLayout.layout(input)
            val before = TreeLayout.layout(input.copy(leaves = input.leaves.filter { it.id != leafId }))
            val keys = listOfNotNull("area:${leaf.area}", leaf.branch?.let { "branch:${leaf.area}/$it" })
            return keys.associateWith { k ->
                val a = after.strokes.firstOrNull { it.key == k }?.curve?.length() ?: return@associateWith 0f
                val b = before.strokes.firstOrNull { it.key == k }?.curve?.length() ?: 0f
                (b / a).coerceIn(0f, 1f)
            }
        }
    }
}
