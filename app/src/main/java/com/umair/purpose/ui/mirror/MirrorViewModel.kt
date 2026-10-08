package com.umair.purpose.ui.mirror

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.umair.purpose.data.db.Letter
import com.umair.purpose.data.repo.ChatRepository
import com.umair.purpose.data.repo.LetterRepository
import com.umair.purpose.data.repo.SessionStart
import com.umair.purpose.data.repo.UiPrefs
import com.umair.purpose.letter.LetterWriter
import com.umair.purpose.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.paging.cachedIn
import javax.inject.Inject

data class MirrorUiState(
    val loaded: Boolean = false,
    /** How many letters there are (they themselves load a page at a time). */
    val letterCount: Int = 0,
    /** UPDATE-15: life chapters, newest first. A few a year, so all at once. */
    val chapters: List<com.umair.purpose.data.db.Chapter> = emptyList(),
    val canWriteNow: Boolean = false,
    val writing: Boolean = false,
    /** A letter that failed after all retries: "weekly", "monthly" or "yearly". */
    val delayed: String? = null,
)

@HiltViewModel
class MirrorViewModel @Inject constructor(
    private val letters: LetterRepository,
    private val writer: LetterWriter,
    private val work: WorkScheduler,
    private val uiPrefs: UiPrefs,
) : ViewModel() {
    private val canWrite = MutableStateFlow(false)

    /** UPDATE-15: letters a page at a time. */
    val pages: kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<Letter>> = letters.paged().cachedIn(viewModelScope)

    val state: StateFlow<MirrorUiState> = combine(
        letters.observeCount().onEach { refreshCanWrite() },
        work.letterNowState(),
        canWrite,
        uiPrefs.letterDelayed,
        letters.observeChapters(),
    ) { count, workState, can, delayed, chapters ->
        MirrorUiState(
            loaded = true,
            letterCount = count,
            chapters = chapters,
            canWriteNow = can,
            writing = workState == WorkInfo.State.ENQUEUED || workState == WorkInfo.State.RUNNING || workState == WorkInfo.State.BLOCKED,
            delayed = delayed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MirrorUiState())

    fun refreshCanWrite() {
        viewModelScope.launch { canWrite.value = writer.canWriteThisWeek() }
    }

    fun writeNow() = work.writeThisWeekNow()

    /** "Try again" on the delayed line: run the letter check again now. */
    fun tryAgain() {
        uiPrefs.setLetterDelayed(null)
        work.checkLetters()
    }

    fun delete(id: Long) {
        viewModelScope.launch { letters.delete(id) }
    }
}

@HiltViewModel
class LetterViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val letters: LetterRepository,
    private val active: com.umair.purpose.chat.ActiveConversation,
) : ViewModel() {
    private val id: Long = checkNotNull(saved.get<Long>("id"))
    val letter: StateFlow<Letter?> = letters.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { letters.markRead(id, System.currentTimeMillis()) }
    }

    fun delete(then: () -> Unit) {
        viewModelScope.launch { letters.delete(id); then() }
    }

    /** A new conversation whose context holds this letter. */
    fun talkAbout(then: () -> Unit) {
        viewModelScope.launch {
            // Always a new, normal conversation with the letter in its context, never the open journey or mode.
            active.start(SessionStart(letterId = id), System.currentTimeMillis())
            then()
        }
    }
}

/** UPDATE-15: one life chapter, read like a letter. */
@HiltViewModel
class ChapterViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val letters: LetterRepository,
) : ViewModel() {
    private val id: Long = checkNotNull(saved.get<Long>("id"))
    val chapter: StateFlow<com.umair.purpose.data.db.Chapter?> =
        letters.observeChapter(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { letters.markChapterRead(id, System.currentTimeMillis()) }
    }
}
