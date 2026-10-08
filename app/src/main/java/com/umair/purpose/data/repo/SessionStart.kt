package com.umair.purpose.data.repo

import com.umair.purpose.chat.Mode
import com.umair.purpose.data.db.Session

/** How a new session begins. A plain talk has no mode. */
data class SessionStart(
    val mode: Mode? = null,
    val modeDetail: String? = null,
    val journeyDay: Int? = null,
    val letterId: Long? = null,
    /** In memory only: never saved, reflected on, or used by letters. */
    val offTheRecord: Boolean = false,
)

/** Starting and ending conversations: what [com.umair.purpose.chat.ActiveConversation] needs. */
interface SessionStore {
    /** The conversation the next message goes to (off the record first), or null. */
    suspend fun openSession(): Session?

    /** Starts a new session, ending whatever was open. */
    suspend fun startSession(start: SessionStart, now: Long): Session

    /** Ends the open session (it gets reflected on); off the record just vanishes. */
    suspend fun endOpenSession(now: Long)
}
