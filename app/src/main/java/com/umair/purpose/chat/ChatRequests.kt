package com.umair.purpose.chat

import com.umair.purpose.ai.AiDefaults
import com.umair.purpose.ai.AiMessage.Role
import com.umair.purpose.ai.AiRequest
import com.umair.purpose.ai.ToolSchemas
import com.umair.purpose.cost.CostEstimator
import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Session
import com.umair.purpose.data.repo.AppSettings
import com.umair.purpose.data.repo.JourneyRepository
import com.umair.purpose.data.repo.MemoryRepository
import com.umair.purpose.data.repo.PromiseRepository
import com.umair.purpose.data.repo.PromptRepository
import com.umair.purpose.data.repo.UsageRepository
import com.umair.purpose.memory.ContextFormatter
import com.umair.purpose.memory.OnboardingFormat
import com.umair.purpose.memory.OnboardingTopic
import com.umair.purpose.time.TimeFacts
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** A chat request ready to send, and how it was routed. */
data class PreparedChat(val request: AiRequest, val route: ModelRouter.Route) {
    val attempt: ReplyAttempt get() = ReplyAttempt(request, route.tier)
}

/**
 * Builds chat requests in CLAUDE.md's order (Core flow 1) and routes them (Model routing). Used by Talk and by
 * the test bench, so both send exactly what the coach really gets.
 */
@Singleton
class ChatRequests @Inject constructor(
    private val prompts: PromptRepository,
    private val memory: MemoryRepository,
    private val promises: PromiseRepository,
    private val journeys: JourneyRepository,
    private val prefixCache: ChatPrefixCache,
    private val usage: UsageRepository,
    private val onboarding: com.umair.purpose.data.repo.OnboardingRepository,
    private val receipts: ActionReceiptStore,
) {
    /**
     * [prefix] overrides the stored-memory prefix (the test bench uses an empty context).
     * Off-the-record sessions get the real memory (the coach still knows him) but nothing is learned from them.
     */
    suspend fun prepare(
        session: Session,
        history: List<Message>,
        settings: AppSettings,
        listenOnly: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        prefix: ChatPromptBuilder.StablePrefix? = null,
        /** The test bench's sample values for the values step; null = his own from the values sort. */
        sampleValues: List<String>? = null,
    ): PreparedChat {
        val nowZoned = ZonedDateTime.now(zone)
        val day = nowZoned.toLocalDate()
        val mode = Mode.fromWire(session.mode)
        val modeInstructions = mode?.let { m ->
            val base = prompts.load(m.promptFile)
            when {
                m == Mode.JOURNEY -> {
                    // The run's own (possibly adapted) days (UPDATE-18).
                    val step = journeys.planForSession(session.modeDetail)?.step(session.journeyDay ?: 1)
                    base + (step?.let { "\n\nToday's step from journeys.md:\n${it.line()}" } ?: "")
                }
                // UPDATE-13: the values step always carries his values, in his order.
                m == Mode.ONBOARDING && session.modeDetail == OnboardingTopic.VALUES.step ->
                    base.trimEnd() + "\n\n" + OnboardingFormat.valuesStepBlock(sampleValues ?: onboarding.values())
                else -> base
            }
        }
        val userMessages = history.filter { it.role == Message.ROLE_USER }.map { it.content }
        val route = ModelRouter.route(
            ModelRouter.Input(
                message = userMessages.lastOrNull().orEmpty(),
                earlierUserMessages = userMessages.dropLast(1),
                modeActive = mode != null,
                alwaysDeep = settings.alwaysDeep,
                overBudget = prefix == null && overBudget(settings),
            )
        )
        val deep = route.tier == Tier.DEEP
        // The tool schema rides at the top of the session-stable prefix, so it stays cached all session.
        val stable = prefix ?: prefixCache.getOrBuild(session.id) {
            memory.chatPrefix(session, prompts.chatSystemPrompt(), day, zone).copy(toolSchemas = ToolSchemas.block)
        }
        val request = AiRequest(
            // Both tiers get the same messages, so the cached prefix is shared.
            model = if (deep) settings.ai.deepModel else settings.ai.chatModel,
            messages = ChatPromptBuilder.build(
                prefix = stable,
                flags = RuntimeFlags(
                    now = TimeFacts.nowSentence(nowZoned),
                    listenOnly = listenOnly,
                    toughLove = settings.toughLove,
                    mode = mode,
                    onboardingStep = session.modeDetail.takeIf { mode == Mode.ONBOARDING },
                    journeyName = session.modeDetail.takeIf { mode == Mode.JOURNEY },
                    journeyDay = session.journeyDay.takeIf { mode == Mode.JOURNEY },
                    practiceWith = PracticeName.clean(session.modeDetail).takeIf { mode == Mode.PRACTICE },
                    offTheRecord = session.offTheRecord,
                    journeyAdjusted = if (mode == Mode.JOURNEY && prefix == null) journeys.adjustmentFor(session.modeDetail) else null,
                    // The latest result from this conversation; survives a failed API attempt (cache-safe).
                    lastActions = if (prefix != null) null else receipts.forSession(session.id),
                ),
                // UPDATE-19: his messages carry a short timestamp in the payload (never on screen).
                turns = history.map {
                    if (it.role == Message.ROLE_USER) Turn(Role.USER, TimeFacts.messageStamp(it.createdAt, zone) + " " + it.content)
                    else Turn(Role.ASSISTANT, it.content)
                },
                duePromise = if (prefix != null) null else promises.dueForInjection(day)?.let { ContextFormatter.duePromise(it, nowZoned.toLocalDateTime()) },
                timeFacts = if (prefix != null) null else ContextFormatter.timeFacts(promises.open(), nowZoned.toLocalDateTime()),
                currentState = if (prefix != null) null else memory.currentChatState(day),
                modeInstructions = modeInstructions,
                continuity = ConversationContinuity.block(history.map { Turn(if (it.role == Message.ROLE_USER) Role.USER else Role.ASSISTANT, it.content) }, session.summary),
                // The test bench runs on an empty memory: no archive either.
                relevantPast = if (prefix != null) null else memory.relevantPast(userMessages.lastOrNull().orEmpty(), session.id.takeIf { it > 0 }),
                // UPDATE-21: tokenized matches from What I Know, injected as the volatile "relevant insights" block.
                relevantInsights = if (prefix != null) null else memory.relevantInsights(userMessages.lastOrNull().orEmpty()),
            ),
            temperature = settings.ai.chatTemperature,
            thinking = deep,
            // With thinking on, the reasoning counts against the limit too.
            maxTokens = if (deep) AiDefaults.DEEP_CHAT_MAX_TOKENS else AiDefaults.CHAT_MAX_TOKENS,
            // Where the session-stable prefix ends, so a provider with explicit caching reuses all of it.
            cachePrefixMessages = ChatPromptBuilder.stableCount(stable, modeInstructions),
        )
        return PreparedChat(request, route)
    }

    /** Deep's silent fallback: the same messages on Fast, thinking off. Null when the reply is already Fast. */
    fun fallback(prepared: PreparedChat, settings: AppSettings): ReplyAttempt? =
        if (prepared.route.tier != Tier.DEEP) null
        else ReplyAttempt(
            prepared.request.copy(model = settings.ai.chatModel, thinking = false, maxTokens = AiDefaults.CHAT_MAX_TOKENS),
            Tier.FAST,
        )

    /** Cost guard: this month's estimated spend has reached the budget. */
    suspend fun overBudget(settings: AppSettings): Boolean {
        if (settings.monthlyBudget <= 0.0) return false
        return monthCost(settings) >= settings.monthlyBudget
    }

    suspend fun monthCost(settings: AppSettings): Double = CostEstimator.estimate(
        usage.thisMonthByModel(), settings.ai.chatModel, settings.chatPrices, settings.ai.deepModel, settings.deepPrices,
        settings.pricing.offPeakFactor,
    ).usd
}
