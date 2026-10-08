package com.umair.purpose.data.repo

import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Off the record": a conversation that lives in memory only. Never written to the database, never reflected
 * on, never used by letters. Gone when it ends or the app is closed. Its ids are negative, so they can never
 * collide with stored ones.
 */
@Singleton
class OffRecord @Inject constructor() {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val ids = AtomicLong(-1)

    val active: Boolean get() = _session.value != null

    fun start(now: Long, mode: String? = null, modeDetail: String? = null, journeyDay: Int? = null): Session {
        _messages.value = emptyList()
        val s = Session(id = ids.getAndDecrement(), startedAt = now, mode = mode, modeDetail = modeDetail, journeyDay = journeyDay)
        _session.value = s
        return s
    }

    /** Nothing said yet: its clock starts again instead of it timing out. */
    fun restartClock(now: Long) = _session.update { it?.copy(startedAt = now) }

    fun end() {
        _session.value = null
        _messages.value = emptyList()
    }

    fun add(message: Message): Long {
        val id = ids.getAndDecrement()
        _messages.update { it + message.copy(id = id) }
        if (message.role == Message.ROLE_USER) _session.update { it?.copy(userMessageCount = it.userMessageCount + 1) }
        return id
    }

    fun update(id: Long, f: (Message) -> Message) = _messages.update { list -> list.map { if (it.id == id) f(it) else it } }

    fun remove(id: Long) = _messages.update { list -> list.filter { it.id != id } }

    fun setMode(mode: String?, detail: String?, journeyDay: Int?) =
        _session.update { it?.copy(mode = mode, modeDetail = detail, journeyDay = journeyDay) }
}
