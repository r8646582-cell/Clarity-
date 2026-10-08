package com.umair.purpose.ui.path

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umair.purpose.chat.Mode
import com.umair.purpose.data.db.Journey
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.repo.ChatRepository
import com.umair.purpose.data.repo.JourneyRepository
import com.umair.purpose.data.repo.PromiseRepository
import com.umair.purpose.data.repo.SessionStart
import com.umair.purpose.journey.JourneyPlan
import com.umair.purpose.journey.JourneyRules
import com.umair.purpose.promise.Due
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import androidx.paging.cachedIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class PathUiState(
    val loaded: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val journey: Journey? = null,
    val plan: JourneyPlan? = null,
    val plans: List<JourneyPlan> = emptyList(),
    val stepToday: Boolean = false,
    val open: List<Promise> = emptyList(),
    /** "You've kept 6 of your last 7." */
    val recordLine: String? = null,
    /** "That's 4 in a row." */
    val runLine: String? = null,
    /** UPDATE-18: the latest adaptation's reason, under today's step. */
    val adjustment: String? = null,
)

@HiltViewModel
class PathViewModel @Inject constructor(
    private val promises: PromiseRepository,
    private val journeys: JourneyRepository,
    private val active: com.umair.purpose.chat.ActiveConversation,
    trees: com.umair.purpose.growth.TreeRepository,
) : ViewModel() {
    /** The growth tree preview at the top of Path (UPDATE-18), laid out off the main thread. */
    val tree: StateFlow<com.umair.purpose.growth.TreeGeometry?> = trees.observe()
        .map { com.umair.purpose.growth.TreeLayout.layout(it.input) }
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The journey under way (active or paused), with its own adapted days and the latest adjustment. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val current: Flow<Triple<Journey?, JourneyPlan?, String?>> = combine(journeys.observeCurrent(), journeys.observeCatalog()) { j, _ -> j }
        .flatMapLatest { j ->
            if (j == null) flowOf(Triple(null, null, null))
            else journeys.observeLatestAdjustment(j.id).map { a ->
                Triple(j, journeys.runPlan(j), a?.takeIf { it.decision != "continue" && it.reason.isNotBlank() }?.reason)
            }
        }
    /** Read again whenever journeys change, so a custom one just made shows its steps straight away. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val plans: Flow<List<JourneyPlan>> = journeys.observeCatalog()

    /** UPDATE-15: past promises, a page at a time. */
    val pastPages = promises.pagedPast().cachedIn(viewModelScope)

    val state: StateFlow<PathUiState> = combine(
        combine(promises.observeOpen(), promises.observeRecentResolved(), ::Pair), current, plans,
        com.umair.purpose.time.calendarDays(),
    ) { (openOnes, resolved), (j, runPlan, adjustment), ps, today ->
        val last = resolved.take(7)
        val run = resolved.takeWhile { it.status == Promise.KEPT }.size
        PathUiState(
            loaded = true,
            today = today,
            journey = j,
            plan = runPlan ?: j?.let { a -> ps.firstOrNull { it.name == a.name } },
            adjustment = adjustment,
            plans = ps,
            stepToday = j != null && JourneyRules.stepAvailableToday(j, today),
            // Soonest due first; undated ones last.
            open = openOnes.sortedWith(compareBy<Promise> { it.dueAt == null }.thenBy { it.dueAt }.thenBy { it.createdAt }),
            recordLine = if (resolved.size >= 3) "You've kept ${last.count { it.status == Promise.KEPT }} of your last ${last.size}." else null,
            runLine = if (resolved.size >= 3 && run >= 2) "That's $run in a row." else null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PathUiState())

    fun markKept(id: Long) = launch { promises.markKept(id, now()) }
    fun letGo(id: Long) = launch { promises.drop(id, now()) }
    fun removeReminder(id: Long) = launch { promises.removeReminder(id) }
    fun edit(id: Long, text: String, due: Due?) = launch { promises.edit(id, text, due) }
    fun undo(p: Promise) = launch { promises.restore(p) }

    fun stopJourney() = launch { journeys.stop() }

    /** UPDATE-18: a paused journey goes on from the same day. */
    fun resumeJourney() = launch { journeys.resume() }

    /** Starts the journey (if needed) and today's step as a Talk session. */
    fun startJourney(name: String, then: () -> Unit) = launch {
        val j = journeys.start(name, now()) ?: return@launch
        active.start(SessionStart(Mode.JOURNEY, j.name, j.currentDay), now())
        then()
    }

    fun startTodaysStep(then: () -> Unit) = launch {
        val j = journeys.active() ?: return@launch
        if (!JourneyRules.stepAvailableToday(j, LocalDate.now())) return@launch
        active.start(SessionStart(Mode.JOURNEY, j.name, j.currentDay), now())
        then()
    }

    /**
     * Phase 0 fallback: he says he did today's step. His own tap is evidence; the coach sees "today's step is done"
     * in its next context line. Same one-step-a-day rule as the coach's advance_journey.
     */
    fun markTodaysStepDone() = launch {
        val j = journeys.active() ?: return@launch
        journeys.advance(j.name, j.currentDay, LocalDate.now())
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun now() = System.currentTimeMillis()
}
