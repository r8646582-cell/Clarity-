package com.umair.purpose.ui.talk

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.umair.purpose.chat.ActiveReply
import com.umair.purpose.chat.ChatPrefixCache
import com.umair.purpose.chat.Conversation
import com.umair.purpose.chat.Mode
import com.umair.purpose.chat.ReplyEngine
import com.umair.purpose.chat.ReplyEvent
import com.umair.purpose.chat.TalkCopy
import com.umair.purpose.data.db.Journey
import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.Pulse
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.Session
import com.umair.purpose.data.repo.AppSettings
import com.umair.purpose.data.repo.ChatRepository
import com.umair.purpose.data.repo.JourneyRepository
import com.umair.purpose.data.repo.OnboardingRepository
import com.umair.purpose.data.repo.OnboardingState
import com.umair.purpose.data.repo.PromiseRepository
import com.umair.purpose.data.repo.PulseRepository
import com.umair.purpose.data.repo.SessionStart
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UiPrefs
import com.umair.purpose.journey.JourneyPlan
import com.umair.purpose.journey.JourneyRules
import com.umair.purpose.memory.OnboardingTopic
import com.umair.purpose.security.SecretStore
import com.umair.purpose.system.Reliability
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** One Talk card at a time, in this priority: onboarding, keep Purpose reliable, today's journey step, daily pulse. */
sealed interface TalkCard {
    val kind: String

    /** Welcome, values and Big Five aren't done yet. */
    data object OnboardingIntro : TalkCard { override val kind = "onboarding" }
    data class OnboardingTopicCard(val number: Int, val topic: OnboardingTopic) : TalkCard { override val kind = "onboarding" }
    /** Shown once, after onboarding, while battery optimization still holds Purpose back. */
    data object KeepReliable : TalkCard { override val kind = "reliable" }
    data class JourneyStep(val journey: Journey, val theme: String) : TalkCard { override val kind = "journey" }
    /** UPDATE-18: "Something I've noticed": a growth-tree leaf with real evidence, for him to accept or not. */
    data class Milestone(val milestone: com.umair.purpose.data.db.Milestone) : TalkCard { override val kind = "milestone" }
    data object PulseCheck : TalkCard { override val kind = "pulse" }
}

data class TalkUiState(
    val loaded: Boolean = false,
    val session: Session? = null,
    val messages: List<Message> = emptyList(),
    /** A reply for this conversation is on its way (it may be in the messages already, streaming). */
    val sending: Boolean = false,
    val error: String? = null,
    val listenOnly: Boolean = false,
    /** The reply on its way is from the deep model: show "thinking…" under the dots. */
    val deep: Boolean = false,
    val hasApiKey: Boolean = true,
    val greeting: String = "",
    val opening: String = "",
    /** UPDATE-19: promises due in the next 48 hours, computed in code. */
    val comingUp: List<com.umair.purpose.chat.ComingUp> = emptyList(),
    /** UPDATE-19: a recent conversation that didn't clearly end. */
    val pickUp: com.umair.purpose.chat.PickUp? = null,
    /** A plain new conversation: the greeting is the big line, "What's on your mind?" sits under it. */
    val plainHome: Boolean = false,
    val card: TalkCard? = null,
    val modeHeader: String? = null,
    /** Promises saved from a coach reply, by message id (a reply can save more than one). */
    val savedPromises: Map<Long, List<Promise>> = emptyMap(),
    val actionReceipts: Map<Long, List<com.umair.purpose.chat.ActionReceipt>> = emptyMap(),
    /** A "getting to know you" step this conversation just finished: "done / Next" under that reply. */
    val stepDone: StepDoneUi? = null,
    val inCharacter: Set<Long> = emptySet(),
    val practiceWith: String? = null,
    val settings: AppSettings? = null,
    /** New user with a key: open the onboarding welcome. */
    val autoOnboarding: Boolean = false,
    /** This conversation lives in memory only. */
    val offTheRecord: Boolean = false,
    /** A past conversation opened from the drawer; sending a message continues it. */
    val viewingPast: Boolean = false,
    /** A reply is still being written in another conversation: sending here waits for it. */
    val replyElsewhere: Boolean = false,
    /** UPDATE-16: no internet. Everything still opens; new messages wait and go out when it's back. */
    val offline: Boolean = false,
    /** UPDATE-16: the main AI provider failed 3 times in a row; the backup is answering for now. */
    val usingBackupAi: Boolean = false,
) {
    val isNew: Boolean get() = messages.isEmpty() && !sending
    /** Last message is his and no reply is coming (failed before any words arrived). Never for one still waiting to go out. */
    val canRetry: Boolean get() = !sending && !viewingPast && !offline &&
        messages.lastOrNull()?.let { it.role == Message.ROLE_USER && !it.queued } == true
}

/** "Your story: done" / "Next: Your people", under the reply that closed the step. [next] null: all five done. */
data class StepDoneUi(val messageId: Long, val done: OnboardingTopic, val next: OnboardingTopic?)

sealed interface TalkEvent {
    data object AskNotificationPermission : TalkEvent
    /** The coach suggested a journey that doesn't exist: show the real ones to pick from. */
    data object PickJourney : TalkEvent
    /** He added a leaf: open the tree and let it grow. */
    data class Grow(val leafId: Long) : TalkEvent
}

private data class Transient(
    val error: String? = null,
    /** The conversation the error belongs to, so it is shown only there. */
    val errorSessionId: Long? = null,
    val listenOnly: Boolean = false,
    /** Between tapping send and the reply engine taking over (so "No reply yet" never flashes). */
    val pending: Boolean = false,
)

private data class Background(
    val settings: AppSettings,
    val onboarding: OnboardingState,
    val journey: Journey?,
    val pulseToday: Pulse?,
    val dismissed: Set<String>,
    val proposal: com.umair.purpose.data.db.Milestone? = null,
)

private data class Extras(
    val day: LocalDate,
    val firstSession: Long?,
    val plans: List<JourneyPlan>,
    val reply: ActiveReply?,
    /** Show the one-time "Keep Purpose reliable" card. */
    val reliableCard: Boolean,
    val stepDone: com.umair.purpose.data.repo.StepDoneCard?,
    val online: Boolean = true,
    val usingBackupAi: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TalkViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chat: ChatRepository,
    private val settings: SettingsRepository,
    private val promises: PromiseRepository,
    private val journeys: JourneyRepository,
    private val onboarding: OnboardingRepository,
    private val pulses: PulseRepository,
    private val prefixCache: ChatPrefixCache,
    private val engine: ReplyEngine,
    private val uiPrefs: UiPrefs,
    private val speaker: Speaker,
    private val db: PurposeDatabase,
    private val active: com.umair.purpose.chat.ActiveConversation,
    private val connectivity: com.umair.purpose.system.Connectivity,
    private val growth: com.umair.purpose.growth.GrowthEngine,
    receipts: com.umair.purpose.chat.ActionReceiptStore,
    failover: com.umair.purpose.ai.FailoverPolicy,
    secrets: SecretStore,
) : ViewModel() {

    private val transient = MutableStateFlow(Transient())
    private val events = Channel<TalkEvent>(Channel.BUFFERED)
    val eventFlow: Flow<TalkEvent> = events.receiveAsFlow()

    private val today = MutableStateFlow(LocalDate.now())
    private val firstSession = MutableStateFlow<Long?>(-1L)
    /** Read again whenever journeys change, so one made with "Make one for me" has its steps and card. */
    private val plans: Flow<List<JourneyPlan>> = journeys.observeCatalog()
    private val batteryRestricted = MutableStateFlow(false)

    /** A past conversation he opened from the drawer (kept in [active], the one source of truth). */
    private val viewing = active.viewing

    private val session: Flow<Session?> = combine(chat.observeOpenSession(), viewing) { open, v -> open to v }
        .flatMapLatest { (open, v) -> if (v == null || v == open?.id) flowOf(open) else chat.observeSession(v) }

    /** The reply's text as it streams, kept apart from [state] so chunks don't rebuild the whole screen state. */
    val live: StateFlow<com.umair.purpose.chat.LiveText?> = engine.live

    /** The conversations drawer. */
    val drawerQuery = MutableStateFlow("")
    /** UPDATE-15: a page at a time, with group labels between (Today, Yesterday, …, then months). */
    val conversations: Flow<androidx.paging.PagingData<com.umair.purpose.chat.DrawerItem>> = drawerQuery
        .flatMapLatest { chat.pagedConversations(it) }
        .map { pd ->
            val zone = ZoneId.systemDefault()
            val day = LocalDate.now(zone)
            pd.map<Conversation, com.umair.purpose.chat.DrawerItem> { com.umair.purpose.chat.DrawerItem.Row(it) }
                .insertSeparators { before, after ->
                    val a = (after as? com.umair.purpose.chat.DrawerItem.Row)?.conversation ?: return@insertSeparators null
                    val label = com.umair.purpose.chat.Conversations.groupLabel(a.lastAt, day, zone)
                    val prev = (before as? com.umair.purpose.chat.DrawerItem.Row)?.conversation
                    if (prev == null || com.umair.purpose.chat.Conversations.groupLabel(prev.lastAt, day, zone) != label) {
                        com.umair.purpose.chat.DrawerItem.Header(label)
                    } else null
                }
        }
        .cachedIn(viewModelScope)

    /**
     * UPDATE-15: a long conversation shows its latest [MESSAGE_PAGE] messages; scrolling to the top loads the next
     * [MESSAGE_PAGE] before them. Kept per conversation, so opening another starts at its latest again.
     */
    private val window = MutableStateFlow<Pair<Long?, Int>>(null to MESSAGE_PAGE)

    private val sessionData: Flow<Triple<Session?, List<Message>, List<Promise>>> = session.flatMapLatest { s ->
        if (s == null) flowOf(Triple(null, emptyList(), emptyList()))
        else window.map { (id, n) -> if (id == s.id) n else MESSAGE_PAGE }.distinctUntilChanged().flatMapLatest { n ->
            combine(chat.observeLatestMessages(s.id, n), promises.observeForSession(s.id)) { m, p -> Triple(s, m, p) }
        }
    }

    /** Scrolled to the top of what's loaded: the next page of older messages, if there are any. */
    fun loadOlder() {
        val s = state.value.session ?: return
        val (id, n) = window.value
        val shown = if (id == s.id) n else MESSAGE_PAGE
        if (state.value.messages.size < shown) return
        window.value = s.id to shown + MESSAGE_PAGE
    }

    private val background: Flow<Background> = combine(
        combine(settings.observe(), onboarding.observe(), ::Pair),
        journeys.observeActive(),
        today.flatMapLatest { pulses.observe(it) },
        uiPrefs.dismissed,
        today.flatMapLatest { growth.observeWaiting(System.currentTimeMillis()) },
    ) { (s, o), j, p, d, waiting -> Background(s, o, j, p, d, waiting.firstOrNull()) }

    private val extras: Flow<Extras> = combine(
        combine(today, firstSession, plans) { a, b, c -> Triple(a, b, c) },
        engine.active,
        combine(batteryRestricted, uiPrefs.reliabilityCardDone) { r, d -> r && !d },
        uiPrefs.stepDone,
        combine(connectivity.online, failover.usingBackup) { on, backup -> on to backup },
    ) { (day, first, journeyPlans), reply, reliable, stepDone, (online, backup) ->
        Extras(day, first, journeyPlans, reply, reliable, stepDone, online, backup)
    }

    /** UPDATE-19 "Talk: home": open promises and recent conversations, re-read each minute for the time phrases. */
    private val home: Flow<Pair<List<Promise>, List<Conversation>>> = combine(
        promises.observeOpen(), chat.observeRecentConversations(), minuteTicks(),
    ) { p, c, _ -> p to c }

    val state: StateFlow<TalkUiState> = combine(
        sessionData, transient, secrets.hasApiKey, background, combine(extras, home, receipts.messages, ::Triple),
    ) { (s, msgs, saved), t, hasKey, bg, (x, h, results) ->
        val nowZoned = java.time.ZonedDateTime.now()
        val time = nowZoned.toLocalTime()
        val plain = s?.mode == null && s?.letterId == null
        val journeyTheme = s?.takeIf { it.mode == Mode.JOURNEY.wire }?.let { sess ->
            val plan = x.plans.firstOrNull { it.name == sess.modeDetail }
            val run = bg.journey?.takeIf { it.name == sess.modeDetail }
            (if (run != null) com.umair.purpose.journey.JourneyAdaptation.steps(run, plan) else plan?.steps.orEmpty())
                .firstOrNull { it.day == (sess.journeyDay ?: 1) }?.theme
        }
        val replying = s != null && (x.reply?.sessionId == s.id || t.pending)
        TalkUiState(
            loaded = true,
            session = s,
            messages = msgs,
            sending = replying,
            error = t.error.takeIf { t.errorSessionId == null || t.errorSessionId == s?.id },
            listenOnly = t.listenOnly,
            deep = replying && x.reply?.deep == true,
            hasApiKey = hasKey,
            greeting = TalkCopy.greeting(time).let { g -> if (g.endsWith("?")) g else "$g, Umair." },
            opening = if (plain) "What's on your mind?" else TalkCopy.opening(s, time, journeyTheme),
            plainHome = plain,
            comingUp = if (plain) com.umair.purpose.chat.HomeFacts.comingUp(h.first, nowZoned.toLocalDateTime()) else emptyList(),
            pickUp = if (plain && s?.offTheRecord != true) com.umair.purpose.chat.HomeFacts.pickUp(h.second, s?.id, nowZoned) else null,
            // Cards belong to a fresh, plain conversation: never in a mode, about a letter, or off the record.
            card = if (s == null || (s.mode == null && s.letterId == null && !s.offTheRecord)) card(bg, x) else null,
            modeHeader = TalkCopy.modeHeader(s),
            savedPromises = saved.filter { it.sourceMessageId != null }.groupBy { it.sourceMessageId!! },
            actionReceipts = results.filterKeys { it.first == s?.id }.mapKeys { it.key.second },
            stepDone = x.stepDone?.takeIf { s != null && it.sessionId == s.id }?.let { c ->
                OnboardingTopic.fromStep(c.step)?.let { StepDoneUi(c.messageId, it, bg.onboarding.nextTopic) }
            },
            inCharacter = if (s?.mode == Mode.PRACTICE.wire) TalkCopy.inCharacter(msgs, s.modeDetail) else emptySet(),
            practiceWith = s?.takeIf { it.mode == Mode.PRACTICE.wire }?.modeDetail?.let(com.umair.purpose.chat.PracticeName::clean),
            settings = bg.settings,
            autoOnboarding = hasKey && x.firstSession == null && !bg.onboarding.welcomeSeen,
            offTheRecord = s?.offTheRecord == true,
            viewingPast = s != null && !s.offTheRecord && s.endedAt != null,
            replyElsewhere = x.reply != null && x.reply.sessionId != s?.id,
            offline = !x.online,
            usingBackupAi = x.usingBackupAi,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TalkUiState())

    init {
        // Open across midnight: the day-based cards and journey step follow the calendar, not just onResume.
        viewModelScope.launch { com.umair.purpose.time.calendarDays().collect { today.value = it } }
        viewModelScope.launch {
            engine.events.collect { e ->
                when (e) {
                    is ReplyEvent.Failed -> transient.update { it.copy(error = e.message, errorSessionId = e.sessionId) }
                    ReplyEvent.WantsReminder -> if (!uiPrefs.askedNotificationPermission) {
                        uiPrefs.askedNotificationPermission = true
                        events.send(TalkEvent.AskNotificationPermission)
                    }
                    is ReplyEvent.PickJourney -> events.send(TalkEvent.PickJourney)
                }
            }
        }
    }

    private fun card(bg: Background, x: Extras): TalkCard? {
        val zone = ZoneId.systemDefault()
        val day = x.day
        fun dismissed(kind: String) = "$kind:$day" in bg.dismissed
        val o = bg.onboarding
        val introDone = o.welcomeSeen && o.valuesDone && o.bigFiveDone
        if (!o.allTopicsDone && !dismissed("onboarding")) {
            if (!introDone) return TalkCard.OnboardingIntro
            val next = o.nextTopic
            // The count is every step done so far, in whatever order they were done.
            if (next != null && o.topicOfferedToday(day, zone)) return TalkCard.OnboardingTopicCard(o.topicsDone + 1, next)
        }
        if (introDone && x.reliableCard) return TalkCard.KeepReliable
        // A leaf with real evidence, waiting for his answer: it stays until he answers (swiping hides it for today).
        bg.proposal?.takeIf { !dismissed("milestone") }?.let { return TalkCard.Milestone(it) }
        val j = bg.journey
        if (j != null && JourneyRules.stepAvailableToday(j, day) && !dismissed("journey")) {
            // The run's own days: an adapted journey shows its adjusted step (UPDATE-18).
            val theme = com.umair.purpose.journey.JourneyAdaptation.steps(j, x.plans.firstOrNull { it.name == j.name })
                .firstOrNull { it.day == j.currentDay }?.theme
            if (theme != null) return TalkCard.JourneyStep(j, theme)
        }
        if (bg.settings.pulseEnabled && bg.pulseToday == null && !dismissed("pulse")) return TalkCard.PulseCheck
        return null
    }

    /** Called whenever the screen comes back: a long pause or a new day ends the old conversation. */
    fun onResume() {
        today.value = LocalDate.now()
        batteryRestricted.value = !Reliability.batteryUnrestricted(context)
        viewModelScope.launch { firstSession.value = db.sessionDao().firstStartedAt() }
        if (engine.busy) return
        viewModelScope.launch {
            // No reply is running, so nothing may still look like it's on its way.
            engine.recoverAfterRestart()
            chat.closeIfStale(now())
        }
    }

    fun setListenOnly(on: Boolean) = transient.update { it.copy(listenOnly = on) }

    fun dismissCard(card: TalkCard) {
        if (card == TalkCard.KeepReliable) uiPrefs.setReliabilityCardDone()
        else uiPrefs.dismiss(card.kind, LocalDate.now().toString())
    }

    /** "Add to your tree": it becomes a leaf, and the tree opens to grow it. */
    fun acceptMilestone(m: com.umair.purpose.data.db.Milestone) {
        viewModelScope.launch {
            growth.accept(m.id)?.let { events.send(TalkEvent.Grow(it.id)) }
        }
    }

    /** "Not yet": back in 30 days. */
    fun snoozeMilestone(m: com.umair.purpose.data.db.Milestone) {
        viewModelScope.launch { growth.snooze(m.id) }
    }

    /** "This isn't right": never again. */
    fun declineMilestone(m: com.umair.purpose.data.db.Milestone) {
        viewModelScope.launch { growth.decline(m.id) }
    }

    fun savePulse(mood: Int, energy: Int, word: String) {
        viewModelScope.launch { pulses.save(LocalDate.now(), mood, energy, word) }
    }

    fun startOnboardingTopic(topic: OnboardingTopic) = start(SessionStart(Mode.ONBOARDING, topic.step))

    /** "Continue now" on a finished step: the next one, as a new conversation (this one ends and is reflected on). */
    fun continueOnboarding(next: OnboardingTopic) {
        uiPrefs.setStepDone(null)
        startOnboardingTopic(next)
    }

    /** "Later": the card goes; the next step waits on Talk's card. */
    fun dismissStepDone() = uiPrefs.setStepDone(null)

    fun startJourneyStep(j: Journey) = start(SessionStart(Mode.JOURNEY, j.name, j.currentDay))

    fun startPractice(with: String) {
        val who = com.umair.purpose.chat.PracticeName.clean(with)
        if (who != null) start(SessionStart(Mode.PRACTICE, who))
    }

    fun startDecision() = start(SessionStart(Mode.DECISION))

    fun startUntangle() = start(SessionStart(Mode.UNTANGLE))

    /** A conversation that is never saved, reflected on, or used by letters. */
    fun startOffTheRecord() = start(SessionStart(offTheRecord = true))

    fun startJourney(name: String) {
        viewModelScope.launch {
            val j = journeys.start(name, now()) ?: return@launch
            startNow(SessionStart(Mode.JOURNEY, j.name, j.currentDay))
        }
    }

    private fun start(s: SessionStart) {
        viewModelScope.launch { startNow(s) }
    }

    private suspend fun startNow(s: SessionStart) {
        speaker.stop()
        active.start(s, now())
        transient.update { Transient(listenOnly = it.listenOnly) }
    }

    /**
     * Drawer: open a conversation to read, and continue it by sending. Allowed while a reply is on its way: that reply belongs to its own conversation (by id) and finishes there.
     * (It used to silently ignore the tap.) Sending waits until it's done.
     */
    fun openConversation(id: Long) {
        speaker.stop()
        viewModelScope.launch {
            active.view(id, chat.openSession()?.id)
            transient.update { Transient(listenOnly = it.listenOnly) }
        }
    }

    /** Ends the current conversation (it gets reflected on) and shows a fresh one with the opening line. */
    fun newConversation() {
        speaker.stop()
        viewModelScope.launch {
            active.newConversation(now())
            transient.update { Transient(listenOnly = it.listenOnly) }
        }
    }

    fun renameConversation(id: Long, title: String) {
        viewModelScope.launch { chat.rename(id, title) }
    }

    /** "Undo" on "Promise saved": it goes, and so does its reminder. */
    fun undoSavedPromise(p: Promise) {
        viewModelScope.launch { promises.deleteSaved(p) }
    }

    fun forgetConversation(id: Long) {
        viewModelScope.launch {
            if (viewing.value == id) active.stopViewing()
            if (engine.active.value?.sessionId == id) engine.cancel()
            if (chat.openSession()?.id == id) transient.update { Transient(listenOnly = it.listenOnly) }
            chat.forget(id)
        }
    }

    /**
     * Sends his message. Returns false (and nothing is sent, so the screen keeps his text) while a reply is still
     * on its way or the last send hasn't landed yet: a double tap can never send twice.
     */
    fun send(text: String): Boolean {
        val content = text.trim()
        if (content.isEmpty() || engine.busy || transient.value.pending) return false
        transient.update { it.copy(error = null, pending = true) }
        val shownOpening = state.value.opening
        val continuing = viewing.value
        val offTheRecord = state.value.offTheRecord && continuing == null
        val listen = transient.value.listenOnly
        viewModelScope.launch {
            try {
                // Continuing a past conversation makes it the open one again (the other one ends and is reflected on).
                val session = continuing?.let { chat.reopen(it, now()) }?.also { active.stopViewing() }
                    ?: chat.ensureOpenSession(now(), offTheRecord)
                // The opening line he saw becomes the first thing Purpose said, so it remembers saying it.
                if (chat.messages(session.id).isEmpty() && shownOpening.isNotBlank()) {
                    chat.addMessage(session.id, Message.ROLE_ASSISTANT, shownOpening, now())
                }
                // UPDATE-16: no internet, or earlier messages still waiting: this one waits too, in order.
                val waiting = !connectivity.isOnline() || chat.messages(session.id).any { it.queued }
                chat.addMessage(session.id, Message.ROLE_USER, content, now(), queued = waiting)
                // The reply is made outside this screen, so leaving the app doesn't stop it.
                if (!waiting) startReply(session.id, listen)
            } finally {
                transient.update { it.copy(pending = false) }
            }
        }
        return true
    }

    /** Starting can throw (a foreground-service restriction): say so quietly instead of crashing the screen. */
    private fun startReply(sessionId: Long, listenOnly: Boolean, replacing: Long? = null) {
        try {
            engine.start(sessionId, listenOnly, replacing = replacing)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            transient.update { it.copy(error = "Couldn't start that reply. Try again.", errorSessionId = sessionId) }
        }
    }

    /** No reply came (it failed before any words arrived): ask again. */
    fun retry() {
        if (engine.busy) return
        transient.update { it.copy(error = null) }
        val s = state.value.session ?: return
        if (state.value.messages.lastOrNull()?.role == Message.ROLE_USER) startReply(s.id, transient.value.listenOnly)
    }

    /** "Try again" on an interrupted reply: regenerate it, replacing the partial one. */
    fun retryInterrupted(messageId: Long) {
        if (engine.busy) return
        transient.update { it.copy(error = null) }
        val s = state.value.session ?: return
        if (state.value.viewingPast) return
        startReply(s.id, transient.value.listenOnly, replacing = messageId)
    }

    /** "End": in every mode. Reading a past one, it only goes back to the current conversation. */
    fun endConversation() {
        speaker.stop()
        viewModelScope.launch {
            active.end(now())
            transient.update { Transient(listenOnly = it.listenOnly) }
        }
    }

    fun speak(text: String) {
        val lang = state.value.settings?.voiceLanguage ?: return
        speaker.speak(text, lang)
    }

    private fun now() = System.currentTimeMillis()

    /** Ticks once a minute so "in 31 hours" and the greeting stay true while Talk is open. */
    private fun minuteTicks(): Flow<Unit> = kotlinx.coroutines.flow.flow {
        while (true) {
            emit(Unit)
            kotlinx.coroutines.delay(60_000)
        }
    }

    private companion object {
        const val MESSAGE_PAGE = 50
    }
}
