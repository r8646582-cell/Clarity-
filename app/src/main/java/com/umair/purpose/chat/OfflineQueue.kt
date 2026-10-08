package com.umair.purpose.chat

import com.umair.purpose.data.repo.ChatRepository
import com.umair.purpose.system.Connectivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UPDATE-16 "Offline": messages written with no internet are kept, shown as "Will send when you're online", and
 * go out in order when the connection returns. One reply answers everything he wrote while away, as in any
 * conversation. Started once by the app; never needs the screen.
 */
@Singleton
class OfflineQueue @Inject constructor(
    private val chat: ChatRepository,
    private val engine: ReplyEngine,
    private val connectivity: Connectivity,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(connectivity.online, engine.active) { online, reply -> online && reply == null }
                .distinctUntilChanged()
                .filter { it }
                .collect { flush() }
        }
    }

    /** Sends whatever waits, one conversation at a time (each reply needs the one before it to finish). */
    suspend fun flush() = lock.withLock {
        for (id in chat.sessionsWithQueued()) {
            if (!connectivity.isOnline()) return@withLock
            // Wait for any reply still running (another conversation's) to finish first.
            while (engine.busy) delay(500)
            while (true) {
                if (!connectivity.isOnline()) return@withLock
                if (engine.start(id, listenOnly = false, queued = true)) break
                delay(500)
            }
        }
    }
}
