package com.umair.purpose.data.repo

import com.umair.purpose.data.db.OnboardingStep
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.memory.BigFive
import com.umair.purpose.memory.BigFiveData
import com.umair.purpose.memory.BigFiveDraft
import com.umair.purpose.memory.ValuesDraft
import com.umair.purpose.memory.OnboardingFormat
import com.umair.purpose.memory.OnboardingSteps
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingRepository @Inject constructor(db: PurposeDatabase) {
    private val dao = db.onboardingDao()
    private val snapshots = db.snapshotDao()

    fun observe(): Flow<OnboardingState> = combine(dao.observeAll(), snapshots.observeLatest()) { steps, snap ->
        OnboardingState(steps.associateBy { it.step }, snap)
    }

    suspend fun state() = OnboardingState(dao.all().associateBy { it.step }, snapshots.latest())

    suspend fun markWelcomeSeen(now: Long) {
        if (dao.get(OnboardingSteps.WELCOME) == null) dao.upsert(OnboardingStep(OnboardingSteps.WELCOME, completedAt = now))
    }

    suspend fun saveValues(values: List<String>, now: Long) =
        dao.upsert(OnboardingStep(OnboardingSteps.VALUES_SORT, completedAt = now, data = OnboardingFormat.encodeValues(values)))

    suspend fun saveBigFive(answers: List<Int>, now: Long) {
        val data = BigFiveData(answers, BigFive.score(answers), itemSet = BigFive.ITEM_SET)
        dao.upsert(OnboardingStep(OnboardingSteps.BIG_FIVE, completedAt = now, data = OnboardingFormat.encodeBigFive(data)))
    }

    /**
     * QA 9.0: answers saved with the old item list included two voting items. Drop them and rescore,
     * in the saved step and in every snapshot. Runs once at launch; does nothing after that.
     */
    suspend fun rescoreLegacyBigFive() {
        dao.get(OnboardingSteps.BIG_FIVE)?.let { step ->
            val fixed = OnboardingFormat.decodeBigFive(step.data)?.let(BigFive::rescoreLegacy)
            if (fixed != null) dao.upsert(step.copy(data = OnboardingFormat.encodeBigFive(fixed)))
        }
        snapshots.all().forEach { snap ->
            val fixed = OnboardingFormat.decodeBigFive(snap.bigFiveJson)?.let(BigFive::rescoreLegacy)
            if (fixed != null) snapshots.setBigFive(snap.id, OnboardingFormat.encodeBigFive(fixed))
        }
    }

    suspend fun valuesDraft(): ValuesDraft = OnboardingFormat.decodeValuesDraft(dao.get(OnboardingSteps.VALUES_DRAFT)?.data)

    suspend fun saveValuesDraft(d: ValuesDraft) =
        dao.upsert(OnboardingStep(OnboardingSteps.VALUES_DRAFT, data = OnboardingFormat.encodeValuesDraft(d)))

    suspend fun bigFiveDraft(): BigFiveDraft = OnboardingFormat.decodeBigFiveDraft(dao.get(OnboardingSteps.BIG_FIVE_DRAFT)?.data)

    suspend fun saveBigFiveDraft(d: BigFiveDraft) =
        dao.upsert(OnboardingStep(OnboardingSteps.BIG_FIVE_DRAFT, data = OnboardingFormat.encodeBigFiveDraft(d)))

    /** Skipped: counts as done, with nothing saved. */
    suspend fun skip(step: String, now: Long) {
        if (dao.get(step)?.completedAt == null) dao.upsert(OnboardingStep(step, completedAt = now))
    }

    /**
     * Steps the coach closed (`[[step_done]]`) or reflection found covered (`onboarding_covered`), in any order.
     * Steps already done stay as they were. Returns true when this made all five done: the one moment the
     * snapshot is written without being asked for (even if he had an early one written).
     */
    suspend fun markDone(steps: Collection<String>, sessionId: Long, now: Long): Boolean {
        val before = state()
        val newly = OnboardingProgress.newlyDone(before, steps)
        newly.forEach { step ->
            val existing = dao.get(step)
            dao.upsert(OnboardingStep(step, sessionId = sessionId, completedAt = now, data = existing?.data))
        }
        return OnboardingProgress.justFinished(before, newly)
    }

    /** His top values from the values sort, in his order (empty until he's done it). */
    suspend fun values(): List<String> = OnboardingFormat.decodeValues(dao.get(OnboardingSteps.VALUES_SORT)?.data)

    /** For the chat context before the snapshot exists. */
    suspend fun soFar(): List<String> {
        val all = dao.all().associateBy { it.step }
        return listOfNotNull(
            OnboardingFormat.valuesLine(all[OnboardingSteps.VALUES_SORT]?.data)?.let { "- Top values he picked, in order: $it" },
            OnboardingFormat.bigFiveLine(all[OnboardingSteps.BIG_FIVE]?.data)
                ?.let { "- Big Five (IPIP-50, a reflection tool, not a diagnosis): $it" },
        )
    }
}
