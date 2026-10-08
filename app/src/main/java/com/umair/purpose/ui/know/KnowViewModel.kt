package com.umair.purpose.ui.know

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.umair.purpose.data.db.AreaStatus
import com.umair.purpose.data.db.BehaviorEvent
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Person
import com.umair.purpose.data.db.ProfileEntry
import com.umair.purpose.data.db.Snapshot
import com.umair.purpose.data.db.Strength
import com.umair.purpose.data.repo.MemoryRepository
import com.umair.purpose.data.repo.OnboardingRepository
import com.umair.purpose.memory.SnapshotWriter
import com.umair.purpose.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import androidx.paging.cachedIn
import javax.inject.Inject

data class KnowUiState(
    val loaded: Boolean = false,
    val profile: List<ProfileEntry> = emptyList(),
    val people: List<Person> = emptyList(),
    val notes: List<Note> = emptyList(),
    val areas: List<AreaStatus> = emptyList(),
    val strengths: List<Strength> = emptyList(),
    val moments: List<BehaviorEvent> = emptyList(),
    /** Archived notes: how many (they load a page at a time). */
    val archivedCount: Int = 0,
    /** Archived profile lines and strengths (over their caps). Few, so all at once. */
    val archivedProfile: List<ProfileEntry> = emptyList(),
    val archivedStrengths: List<Strength> = emptyList(),
    /** UPDATE-15: "4 chapters of your story". */
    val chapters: Int = 0,
    val snapshot: Snapshot? = null,
    val onboardingDone: Boolean = false,
    /** Getting-to-know-you conversations done, of 5. */
    val topicsDone: Int = 0,
    val canRefresh: Boolean = false,
    val writingSnapshot: Boolean = false,
    /** Memory gardening is running: "Cleaning up what I know…". */
    val cleaningUp: Boolean = false,
)

private data class Memory1(val profile: List<ProfileEntry>, val people: List<Person>, val notes: List<Note>, val areas: List<AreaStatus>)
private data class Memory2(val strengths: List<Strength>, val moments: List<BehaviorEvent>, val archivedCount: Int, val chapters: Int)

@HiltViewModel
class KnowViewModel @Inject constructor(
    private val memory: MemoryRepository,
    private val onboarding: OnboardingRepository,
    private val snapshots: SnapshotWriter,
    private val work: WorkScheduler,
    private val active: com.umair.purpose.chat.ActiveConversation,
    private val chat: com.umair.purpose.data.repo.ChatRepository,
    letters: com.umair.purpose.data.repo.LetterRepository,
) : ViewModel() {
    private val canRefresh = MutableStateFlow(false)

    /** UPDATE-15: the archive a page at a time, and searched in the database. */
    val archivedPages = memory.pagedRetiredNotes().cachedIn(viewModelScope)
    val query = MutableStateFlow("")
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val archivedMatches: StateFlow<List<Note>> = query.flatMapLatest { q ->
        if (q.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else memory.searchRetiredNotes(q.trim())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { canRefresh.value = snapshots.canRefresh(System.currentTimeMillis()) }
    }

    val state: StateFlow<KnowUiState> = combine(
        combine(memory.observeProfile(), memory.observePeople(), memory.observeNotes(), memory.observeAreas(), ::Memory1),
        combine(memory.observeStrengths(), memory.observeBehavior(), memory.observeRetiredCount(), letters.observeChapters().map { it.size }, ::Memory2),
        onboarding.observe(),
        combine(work.snapshotState(), work.gardeningState(), ::Pair),
        canRefresh,
    ) { m1, m2, o, (snapState, gardenState), refresh ->
        KnowUiState(
            loaded = true,
            profile = m1.profile.filter { !it.retired },
            archivedProfile = m1.profile.filter { it.retired && it.value.isNotBlank() },
            people = m1.people,
            notes = m1.notes,
            areas = m1.areas.sortedBy { AreaStatus.AREAS.indexOf(it.area) },
            strengths = m2.strengths.filter { !it.retired },
            archivedStrengths = m2.strengths.filter { it.retired },
            moments = m2.moments,
            archivedCount = m2.archivedCount,
            chapters = m2.chapters,
            snapshot = o.snapshot,
            onboardingDone = o.allTopicsDone,
            topicsDone = o.topicsDone,
            canRefresh = refresh && o.allTopicsDone,
            writingSnapshot = snapState == WorkInfo.State.ENQUEUED || snapState == WorkInfo.State.RUNNING,
            cleaningUp = gardenState == WorkInfo.State.RUNNING,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KnowUiState())

    /** "Write it now with what I know": the snapshot from what's there, before all five conversations. */
    fun writeSnapshotNow() {
        work.writeSnapshot()
    }

    /**
     * "Continue": the welcome, values and Big Five first (the onboarding screen), then the next conversation.
     * [openOnboarding] or [openTalk] is called once the next step is ready.
     */
    fun continueOnboarding(openOnboarding: () -> Unit, openTalk: () -> Unit) {
        viewModelScope.launch {
            val o = onboarding.state()
            val next = o.nextTopic
            when {
                !(o.welcomeSeen && o.valuesDone && o.bigFiveDone) -> openOnboarding()
                next != null -> {
                    active.start(com.umair.purpose.data.repo.SessionStart(com.umair.purpose.chat.Mode.ONBOARDING, next.step), now())
                    openTalk()
                }
                else -> openTalk()
            }
        }
    }

    fun refreshSnapshot() {
        canRefresh.value = false
        work.writeSnapshot()
    }

    fun editProfile(key: String, value: String) = launch { memory.editProfile(key, value, now()) }
    fun deleteProfile(e: ProfileEntry) = launch { memory.deleteProfile(e.key, now()) }
    fun restoreProfile(e: ProfileEntry) = launch { memory.restoreProfile(e) }
    fun editPerson(id: Long, name: String, relation: String, notes: String) = launch { memory.editPerson(id, name, relation, notes, now()) }
    fun deletePerson(p: Person) = launch { memory.deletePerson(p.id, now()) }
    fun restorePerson(p: Person) = launch { memory.restorePerson(p) }
    fun editNote(id: Long, text: String) = launch { memory.editNote(id, text) }
    fun deleteNote(n: Note) = launch { memory.deleteNote(n.id) }
    fun restoreNote(n: Note) = launch { memory.restoreNote(n) }
    fun editArea(area: String, status: String, note: String) = launch { memory.editArea(area, status, note, now()) }
    fun deleteArea(a: AreaStatus) = launch { memory.deleteArea(a.area, now()) }
    fun restoreArea(a: AreaStatus) = launch { memory.restoreArea(a) }
    fun editStrength(id: Long, text: String) = launch { memory.editStrength(id, text) }
    fun deleteStrength(s: Strength) = launch { memory.deleteStrength(s.id) }
    fun restoreStrength(s: Strength) = launch { memory.restoreStrength(s) }
    fun deleteMoment(e: BehaviorEvent) = launch { memory.deleteBehavior(e.id) }
    fun restoreMoment(e: BehaviorEvent) = launch { memory.restoreBehavior(e) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    suspend fun sourceConversations(ids: String): List<com.umair.purpose.data.db.Session> =
        com.umair.purpose.memory.Provenance.parse(ids).filter { it > 0 }.sortedDescending().take(5).mapNotNull { chat.session(it) }

    fun openSource(id: Long, openTalk: () -> Unit) = launch {
        if (chat.session(id) != null) {
            active.view(id, chat.openSession()?.id)
            openTalk()
        }
    }

    private fun now() = System.currentTimeMillis()
}
