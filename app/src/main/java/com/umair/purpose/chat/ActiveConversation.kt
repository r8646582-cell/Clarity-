package com.umair.purpose.chat

import com.umair.purpose.data.db.Session
import com.umair.purpose.data.repo.SessionStart
import com.umair.purpose.data.repo.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one source of truth for which conversation Talk shows and in which mode (QA 10.4: "Talk about this
 * letter" landed in the journey chat and got stuck). The open session lives in the database; the only other
 * state is a past conversation he opened from the drawer to read. Every way of starting or ending a
 * conversation, from any screen, goes through here, so a stale "viewing" or a running reply can never pull
 * him back into the old one.
 */
class ActiveConversation(
    private val store: SessionStore,
    /** Stops a reply that's still running (it would otherwise land in the conversation being left). */
    private val cancelReply: () -> Unit,
    /** The cached stable prefix belongs to the old conversation. */
    private val clearPrefix: () -> Unit,
) {
    private val _viewing = MutableStateFlow<Long?>(null)
    /** A past conversation opened from the drawer, until he sends (then it's the open one again) or leaves it. */
    val viewing: StateFlow<Long?> = _viewing.asStateFlow()

    /** Which session Talk shows, given the open one. */
    fun shown(openId: Long?): Long? = _viewing.value?.takeIf { it != openId } ?: openId

    /** A brand-new conversation (a mode, a letter, off the record, or plain): always new, never reused. */
    suspend fun start(start: SessionStart, now: Long): Session {
        cancelReply()
        _viewing.value = null
        val s = store.startSession(start, now)
        clearPrefix()
        return s
    }

    /** "End": works in every mode. Reading a past conversation, it just goes back to the current one. */
    suspend fun end(now: Long) {
        if (_viewing.value != null) {
            _viewing.value = null
            return
        }
        cancelReply()
        store.endOpenSession(now)
        clearPrefix()
    }

    /** "New conversation": always reachable, it leaves whatever is on screen and ends the open one. */
    suspend fun newConversation(now: Long) {
        _viewing.value = null
        cancelReply()
        store.endOpenSession(now)
        clearPrefix()
    }

    fun view(id: Long, openId: Long?) {
        _viewing.value = id.takeIf { it != openId }
    }

    /** He sent a message in the conversation he was reading: it's the open one now. */
    fun stopViewing() {
        _viewing.value = null
    }
}
