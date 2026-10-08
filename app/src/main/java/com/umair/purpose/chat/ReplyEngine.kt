package com.umair.purpose.chat

import android.content.Context
import android.util.Log
import com.umair.purpose.ai.AiClient
import com.umair.purpose.ai.AiException
import com.umair.purpose.data.db.Session
import com.umair.purpose.data.repo.AppSettings
import com.umair.purpose.data.repo.ChatRepository
import com.umair.purpose.data.repo.JourneyRepository
import com.umair.purpose.data.repo.OnboardingRepository
import com.umair.purpose.data.repo.StepDoneCard
import com.umair.purpose.data.repo.UiPrefs
import com.umair.purpose.data.repo.PromiseRepository
import com.umair.purpose.data.repo.ReplyDetails
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UsageRepository
import com.umair.purpose.dev.ErrorLogger
import com.umair.purpose.time.TimeFacts
import com.umair.purpose.ui.talk.Speaker
import com.umair.purpose.work.WorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** The reply on its way, for the typing dots and "thinking…". */
data class ActiveReply(val sessionId: Long, val messageId: Long?, val deep: Boolean)

/**
 * The reply's visible text as it arrives, held in memory and updated on every chunk (UPDATE-11). The screen reads
 * this while the app is open; the database copy is only for persistence. [done]: no more text is coming.
 */
data class LiveText(val messageId: Long, val text: String, val done: Boolean = false)

/** What the screen should hear about once a reply ends. */
sealed interface ReplyEvent {
    data class Failed(val sessionId: Long, val message: String) : ReplyEvent
    /** A reminder was set: ask for notification permission the first time. */
    data object WantsReminder : ReplyEvent
    /** The coach named a journey that doesn't exist (UPDATE-14): nothing starts; the journey list opens instead. */
    data class PickJourney(val sessionId: Long) : ReplyEvent
}

/**
 * CLAUDE.md "Replies survive leaving the app". Generation lives here, application-scoped, not in the UI:
 * while a reply runs, [ReplyService] keeps the process in the foreground, and the reply is written to the
 * database as it streams (partial text about once a second, never hidden `[[…]]` lines), then completed.
 * While the app is open the screen reads the text live from [live]; the database is what it shows after
 * leaving and coming back, wherever the reply got to.
 */
@Singleton
class ReplyEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chat: ChatRepository,
    private val requests: ChatRequests,
    private val settings: SettingsRepository,
    private val promises: PromiseRepository,
    private val journeys: JourneyRepository,
    private val usage: UsageRepository,
    private val errors: ErrorLogger,
    private val speaker: Speaker,
    private val ai: AiClient,
    private val onboarding: OnboardingRepository,
    private val uiPrefs: UiPrefs,
    private val work: WorkScheduler,
    private val actions: ActionExecutor,
    private val receipts: ActionReceiptStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _active = MutableStateFlow<ActiveReply?>(null)
    val active: StateFlow<ActiveReply?> = _active.asStateFlow()

    private val _live = MutableStateFlow<LiveText?>(null)
    val live: StateFlow<LiveText?> = _live.asStateFlow()

    private val _events = Channel<ReplyEvent>(Channel.BUFFERED)
    val events: Flow<ReplyEvent> = _events.receiveAsFlow()

    /** Claimed before any IPC or suspension, so two callers can never both start a reply. */
    private val starting = AtomicBoolean(false)

    val busy: Boolean get() = starting.get() || job?.isActive == true

    /** The reply message being written, so an unexpected error can still settle it. */
    @Volatile private var current: Long? = null

    /** True once the reply reached an end state, so a later failure is not reported as a failed reply. */
    @Volatile private var settled = false

    init {
        chat.onDatasetReplaced {
            _live.value = null
            // Buffered feedback belongs to the old data, even if restored rows reuse its message/session IDs.
            while (_events.tryReceive().isSuccess) { }
        }
    }

    /**
     * Writes Purpose's reply to the conversation's latest message. [replacing]: an interrupted reply to
     * regenerate (it's removed first). Returns false if a reply is already running.
     */
    fun start(sessionId: Long, listenOnly: Boolean, replacing: Long? = null, queued: Boolean = false): Boolean {
        val datasetTicket = chat.datasetTicket() ?: return false
        // Claimed up front: `job` is only assigned after ReplyService.start's IPC, and the callers live on
        // different threads (Talk's send on the main thread, the offline queue on IO), so checking `job` alone
        // let two replies run at once and clobber the single-slot live/active state.
        if (!starting.compareAndSet(false, true)) return false
        settled = false
        _active.value = ActiveReply(sessionId, null, deep = false)
        try {
            ReplyService.start(context)
        } catch (e: Exception) {
            starting.set(false)
            _active.value = null
            throw e
        }
        job = scope.launch {
            try {
                chat.withDatasetWriter(datasetTicket) {
                    if (queued) chat.sendQueued(sessionId)
                    replacing?.let { chat.deleteMessage(it) }
                    runCurrentDataset(sessionId, listenOnly)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Anything unexpected still ends the reply: never a message stuck "streaming".
                errors.log("chat", e)
                // Only a reply that never reached an end state is reported as failed: an exception thrown after
                // the reply completed (saving a promise, scheduling a reminder, speaking it) must not put
                // "Couldn't finish that reply." under a reply that is already finished on screen.
                if (!settled) {
                    runCatching { current?.let { id -> settleLeftover(id) } }
                    _events.send(ReplyEvent.Failed(sessionId, "Couldn't finish that reply."))
                }
            } finally {
                current = null
                _active.value = null
                // Plain call, not withContext: this also runs when the reply was cancelled.
                try {
                    ReplyService.finish(context)
                } finally {
                    starting.set(false)
                }
            }
        }
        return true
    }

    /** He ended the conversation or forgot it mid-reply: stop, and drop the unfinished reply. */
    fun cancel() {
        job?.cancel()
    }

    /** On launch, before anything else: a reply cut off by a killed process is shown as interrupted. */
    suspend fun recoverAfterRestart() {
        if (busy) return
        chat.interruptLeftovers()
    }

    private suspend fun runCurrentDataset(sessionId: Long, listenOnly: Boolean) {
        val receiptGeneration = receipts.generation
        val appSettings = settings.get()
        val session = chat.session(sessionId) ?: return
        val zone = ZoneId.systemDefault()
        val prepared = requests.prepare(session, chat.messages(sessionId), appSettings, listenOnly, zone)
        val messageId = chat.startReply(sessionId, System.currentTimeMillis())
        current = messageId
        _live.value = LiveText(messageId, "")
        _active.value = ActiveReply(sessionId, messageId, deep = prepared.route.tier == Tier.DEEP)

        val outcome = try {
            ReplyRunner(ai).run(
                primary = prepared.attempt,
                fallback = requests.fallback(prepared, appSettings),
                sink = { visible ->
                    chat.updateReply(messageId, visible)
                    // The silent Fast fallback answers without thinking: drop "thinking…".
                    if (visible.isEmpty()) _active.value = ActiveReply(sessionId, messageId, deep = false)
                },
                onUsage = { a, t -> usage.log("chat", a.request.model, t, System.currentTimeMillis(), a.tier.wire) },
                onFailure = { f -> errors.logAttempt("chat", f) },
                live = { visible -> _live.value = LiveText(messageId, visible) },
            )
        } catch (e: CancellationException) {
            // Ended on purpose: no half reply left behind.
            withContext(NonCancellable) { chat.deleteMessage(messageId) }
            throw e
        }

        when (outcome) {
            is ReplyOutcome.Complete -> finishReply(session, messageId, outcome, appSettings, zone, receiptGeneration)
            is ReplyOutcome.CutOff -> {
                // What arrived stays, marked interrupted, with Try again.
                _live.value = LiveText(messageId, outcome.visible, done = true)
                chat.updateReply(messageId, outcome.visible)
                chat.interruptReply(messageId)
                settled = true
                // A cut-off reply is still a reply: say so if he's in another app.
                ReplyService.replied(context)
            }
            is ReplyOutcome.Failed -> {
                // Words already shown stay, marked interrupted with Try again; an empty reply just goes.
                _live.value = LiveText(messageId, outcome.visible, done = true)
                if (outcome.visible.isNotBlank()) {
                    chat.updateReply(messageId, outcome.visible)
                    chat.interruptReply(messageId)
                    settled = true
                    ReplyService.replied(context)
                } else {
                    chat.deleteMessage(messageId)
                    settled = true
                }
                _events.send(ReplyEvent.Failed(sessionId, describe(outcome.error, outcome.visible.isNotBlank())))
            }
        }
    }

    private suspend fun finishReply(session: Session, messageId: Long, outcome: ReplyOutcome.Complete, appSettings: AppSettings, zone: ZoneId, receiptGeneration: Long) {
        val sessionId = session.id
        val request = outcome.attempt.request
        val tokens = outcome.usage
        // Late-night replies belong to the night they started: a promise "tonight"/"tomorrow" is read against
        // the logical day, not the calendar day that has just ticked over.
        val today = ZonedDateTime.now(zone).toLocalDate()
        val parsed = ReplyMarkers.parse(outcome.raw, today)
        if (parsed.failures > 0) {
            Log.w(TAG, "Couldn't read ${parsed.failures} hidden line(s) in a reply")
            errors.logParse("chat", "Couldn't read ${parsed.failures} hidden line(s) in a reply", request.model)
        }
        // The screen finishes revealing the last words from memory while the database catches up.
        if (parsed.visible.isBlank()) {
            // Nothing but hidden lines: there is no prose, so no empty bubble is left in the conversation.
            // The markers below are still applied.
            _live.value = LiveText(messageId, "", done = true)
            chat.deleteMessage(messageId)
        } else {
            _live.value = LiveText(messageId, parsed.visible, done = true)
            chat.completeReply(
                messageId,
                parsed.visible,
                ReplyDetails(
                    tier = outcome.attempt.tier.wire,
                    model = request.model,
                    thinking = request.thinking,
                    inputTokens = tokens?.promptTokens,
                    outputTokens = tokens?.completionTokens,
                    firstTokenMs = outcome.firstVisibleMs,
                    totalMs = outcome.totalMs,
                ),
            )
        }
        // The reply text is durably stored, so it is settled: anything below that throws (saving a promise,
        // scheduling a reminder, switching mode, reading it aloud) is not a failed reply.
        settled = true
        // Hidden lines are acted on only now that the reply is complete. With no bubble to sit under, a promise
        // is saved without a source message rather than pointing at a row that no longer exists.
        val attachTo = messageId.takeIf { it > 0 && parsed.visible.isNotBlank() }
        var wantsReminder = false
        parsed.promises.forEach { line ->
            // Off the record keeps nothing, except a reminder he explicitly asked for.
            if (session.offTheRecord && line.remindAt == null) return@forEach
            val saved = promises.saveFromChat(line, sessionId.takeIf { it > 0 }, attachTo, zone, System.currentTimeMillis())
            if (saved?.remindAt != null) wantsReminder = true
        }
        parsed.mode?.let { applyMode(session, it) }
        // The session-end path advances an explored journey step. Do not complete it halfway through a reply;
        // an explicit advance_journey action below can still record a completed step immediately.
        // The coach asked who it is and named them: the header shows the name from now on.
        parsed.practiceWith?.let { who ->
            val now = chat.session(sessionId)
            if (now?.mode == Mode.PRACTICE.wire) chat.switchMode(sessionId, Mode.PRACTICE, who, null)
        }
        if (parsed.stepsDone.isNotEmpty()) closeOnboardingSteps(session, messageId, parsed.stepsDone)
        // UPDATE-20: run the hidden `[[action: …]]` blocks. Their outcome is kept as a receipt for the next
        // request, so the coach sees what it actually did and can confirm it without a second call.
        if (parsed.actions.isNotEmpty()) {
            val results = actions.execute(parsed.actions, session, attachTo ?: messageId, zone, System.currentTimeMillis())
            receipts.record(sessionId, results, attachTo, receiptGeneration)
            if (results.any { it.ok && it.wantsReminder }) wantsReminder = true
            if (results.any { !it.ok && it.type in setOf("record_promise", "edit_promise", "reschedule_promise", "resolve_promise") }) {
                _events.send(ReplyEvent.Failed(sessionId, "That promise change wasn't saved. Please try again, or change it in Path."))
            } else if (results.any { !it.ok }) {
                _events.send(ReplyEvent.Failed(sessionId, "An app action wasn't completed. Please try again."))
            }
        }
        if (wantsReminder) _events.send(ReplyEvent.WantsReminder)
        if (appSettings.readAloud) withContext(Dispatchers.Main) { speaker.speak(parsed.visible, appSettings.voiceLanguage) }
        ReplyService.replied(context)
    }

    private suspend fun settleLeftover(id: Long) {
        if (chat.message(id)?.streaming != true) return
        // The screen already showed the live text; keep the database in step with it.
        _live.value?.takeIf { it.messageId == id }?.let { l ->
            if (l.text.isNotBlank()) chat.updateReply(id, l.text)
            _live.value = l.copy(done = true)
        }
        val m = chat.message(id) ?: return
        if (m.content.isBlank()) chat.deleteMessage(id) else chat.interruptReply(id)
    }

    /**
     * `[[step_done: …]]` (UPDATE-12): the step is done, the onboarding mode ends for this conversation (the header
     * goes; the talk carries on), and a "done / Next" card shows under this reply. Off the record touches nothing.
     */
    private suspend fun closeOnboardingSteps(session: Session, messageId: Long, steps: List<String>) {
        if (session.offTheRecord) return
        val now = System.currentTimeMillis()
        val current = chat.session(session.id) ?: return
        val allDone = onboarding.markDone(steps, session.id, now)
        val own = current.mode == Mode.ONBOARDING.wire && current.modeDetail in steps
        if (own) {
            chat.switchMode(session.id, null, null, null)
            uiPrefs.setStepDone(StepDoneCard(session.id, messageId, current.modeDetail!!))
        }
        if (allDone) work.writeSnapshot()
    }

    /** He agreed to a suggested mode: the rest of this conversation runs in it. */
    private suspend fun applyMode(session: Session, line: ReplyMarkers.ModeLine) {
        val now = System.currentTimeMillis()
        val sessionId = session.id
        when (line.mode) {
            Mode.JOURNEY -> {
                // Off the record never starts (or stops) a real journey.
                if (session.offTheRecord) return
                val plan = journeys.planExact(line.journeyName)
                if (plan == null) {
                    errors.logParse("chat", "The coach named a journey that doesn't exist", null)
                    _events.send(ReplyEvent.PickJourney(sessionId))
                    return
                }
                val j = journeys.start(plan.name, now) ?: return
                chat.switchMode(sessionId, Mode.JOURNEY, j.name, j.currentDay)
            }
            Mode.PRACTICE -> chat.switchMode(sessionId, Mode.PRACTICE, line.with, null)
            else -> chat.switchMode(sessionId, line.mode, null, null)
        }
    }

    companion object {
        private const val TAG = "Reply"

        fun describe(e: Exception, midway: Boolean): String = when {
            e is AiException && e.message == "No API key set" -> "Add your API key in Settings first."
            e is AiException && e.httpCode == 401 -> "DeepSeek didn't accept the key. Check it in Settings."
            e is AiException && e.httpCode == 402 -> "The DeepSeek account is out of balance. Top it up, then try again."
            e is AiException && e.httpCode == 429 -> "DeepSeek is busy right now. Try again in a moment."
            e is AiException && e.httpCode != null && e.httpCode >= 500 -> "DeepSeek is having trouble. Try again soon."
            midway -> "Couldn't finish that reply."
            e is AiException && e.cause != null -> "Couldn't reach DeepSeek. Check your internet and try again."
            else -> "Couldn't finish that reply."
        }
    }
}
