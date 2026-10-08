package com.umair.purpose.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UPDATE-15 "A failed migration must stop and show an error, never wipe": the database is opened (and migrated)
 * once at launch, before any screen reads it. If that fails, the app shows what happened instead of crashing,
 * and his data stays exactly as it was on the phone for the next version to migrate.
 */
@Singleton
class DatabaseGate @Inject constructor(private val db: PurposeDatabase) {
    sealed interface State {
        data object Opening : State
        data object Ready : State
        /** [details]: the error, for "Copy details" (never any of his content). */
        data class Failed(val details: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Opening)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Opens the database. Safe to call more than once. */
    suspend fun open(): Boolean = withContext(Dispatchers.IO) {
        if (_state.value == State.Ready) return@withContext true
        try {
            db.openHelper.writableDatabase
            _state.value = State.Ready
            true
        } catch (e: Throwable) {
            _state.value = State.Failed(generateSequence(e) { it.cause }.joinToString("\nCaused by: ") { "${it.javaClass.name}: ${it.message}" }.take(2000))
            false
        }
    }

    suspend fun awaitReady() {
        _state.first { it == State.Ready }
    }
}
