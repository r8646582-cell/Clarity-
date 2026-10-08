package com.umair.purpose.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.input.KeyboardType
import com.umair.purpose.ui.common.LabeledField
import com.umair.purpose.ui.common.PurposeTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.umair.purpose.ai.AiClient
import com.umair.purpose.chat.ChatPromptBuilder
import com.umair.purpose.chat.ChatRequests
import com.umair.purpose.chat.Mode
import com.umair.purpose.chat.ReplyMarkers
import com.umair.purpose.chat.ReplyOutcome
import com.umair.purpose.chat.ReplyRunner
import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Session
import com.umair.purpose.data.repo.PromptRepository
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UsageRepository
import com.umair.purpose.cost.CostEstimate
import com.umair.purpose.cost.Prices
import com.umair.purpose.dev.BenchUsage
import com.umair.purpose.dev.ErrorLogger
import com.umair.purpose.dev.Scenario
import com.umair.purpose.dev.ScenarioRun
import com.umair.purpose.dev.TestBench
import com.umair.purpose.memory.ChatExtras
import com.umair.purpose.memory.ContextFormatter
import com.umair.purpose.memory.Memory
import com.umair.purpose.ui.common.Hairline
import com.umair.purpose.ui.common.Meta
import com.umair.purpose.ui.common.PrimaryButton
import com.umair.purpose.ui.common.ScreenHeader
import com.umair.purpose.ui.common.SidePadding
import com.umair.purpose.ui.common.TextAction
import com.umair.purpose.ui.theme.Purpose
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

data class TestBenchState(
    val runs: List<ScenarioRun> = emptyList(),
    /** Index of the scenario being run, or null when idle. */
    val running: Int? = null,
    /** UPDATE-17 "Compare models": the model the last run used instead of his own, or null for his own. */
    val runModel: String? = null,
    /** Cost of the last full run on his own models, and of the last run on the compared model. */
    val ownCost: CostEstimate? = null,
    val compareCost: CostEstimate? = null,
    val comparedModel: String? = null,
)

/**
 * Settings > Advanced > Developer > Test bench. Every scenario runs off the record, with an empty memory and
 * sample help numbers, through the same request builder and routing as Talk. Nothing is saved.
 */
@HiltViewModel
class TestBenchViewModel @Inject constructor(
    private val prompts: PromptRepository,
    private val settings: SettingsRepository,
    private val requests: ChatRequests,
    private val usage: UsageRepository,
    private val errors: ErrorLogger,
    private val ai: AiClient,
    private val journeys: com.umair.purpose.data.repo.JourneyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TestBenchState())
    val state: StateFlow<TestBenchState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val scenarios = TestBench.parse(prompts.load("testbench.md"))
            _state.value = TestBenchState(scenarios.map { ScenarioRun(it, emptyList(), null, null) })
        }
    }

    /** Tokens of the run in progress. */
    private val runUsage = mutableListOf<BenchUsage>()

    fun runAll() = start(null)

    /**
     * UPDATE-17: runs every scenario on [model] (both tiers, so routing doesn't matter) and prices it with
     * [prices] per 1M tokens, next to the cost of his own models' last run.
     */
    fun compare(model: String, prices: Prices) {
        if (model.isBlank()) return
        start(model.trim() to prices)
    }

    private fun start(other: Pair<String, Prices>?) {
        if (job?.isActive == true) return
        runUsage.clear()
        _state.update { s -> s.copy(runModel = other?.first, runs = s.runs.map { it.copy(replies = emptyList(), error = null, verdict = null) }) }
        job = viewModelScope.launch {
            var finished = false
            try {
                _state.value.runs.indices.forEach { i ->
                    _state.update { it.copy(running = i) }
                    val result = runCatching { run(_state.value.runs[i].scenario, i, other?.first) }
                    result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                    _state.update { s ->
                        s.copy(runs = s.runs.mapIndexed { j, r ->
                            if (j != i) r else r.copy(
                                replies = result.getOrNull() ?: r.replies,
                                error = result.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName },
                            )
                        })
                    }
                }
                finished = true
            } finally {
                // A cancelled run isn't a fair price, so only a finished one is shown.
                if (finished) {
                    val usage = runUsage.toList()
                    if (other == null) {
                        val pricing = settings.get().pricing
                        val cost = TestBench.runCost(usage) { pricing.forModel(it) }
                        _state.update { it.copy(ownCost = cost) }
                    } else {
                        val cost = TestBench.runCost(usage) { m -> other.second.takeIf { m == other.first } }
                        _state.update { it.copy(compareCost = cost, comparedModel = other.first) }
                    }
                }
                _state.update { it.copy(running = null) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun judge(index: Int, pass: Boolean) =
        _state.update { s -> s.copy(runs = s.runs.mapIndexed { j, r -> if (j == index) r.copy(verdict = pass) else r }) }

    fun report(): String = TestBench.report(_state.value.runs)

    /** Sends each "User:" line in turn; the coach replies after each. Returns the visible replies. */
    private suspend fun run(scenario: Scenario, index: Int, otherModel: String?): List<String> {
        val appSettings = settings.get()
        val zone = ZoneId.systemDefault()
        val mode = Mode.fromWire(scenario.mode)
        // Negative id: off the record, so the off_the_record flag is set and nothing can be stored.
        val session = Session(
            id = -10_000L - index, startedAt = System.currentTimeMillis(), mode = mode?.wire,
            modeDetail = scenario.step.takeIf { mode == Mode.ONBOARDING },
        )
        // Empty memory; only the help numbers and the journeys that really exist, as in every chat.
        val prefix = ChatPromptBuilder.StablePrefix(
            persona = prompts.chatSystemPrompt(),
            toolSchemas = com.umair.purpose.ai.ToolSchemas.block,
            contextBlock = ContextFormatter.chatContext(
                Memory(emptyList(), emptyList(), emptyList(), emptyList()),
                ChatExtras(availableJourneys = journeys.catalog().map { it.name }),
                zone,
            ),
            // UPDATE-19: the scenario's own facts (a time, a promise, a profile line), as if they were his memory.
            recentSummaries = scenario.setup?.let {
                "Test setup (treat these as the real facts for this conversation; a time given here overrides `now`):\n$it"
            },
        )
        val history = mutableListOf<Message>()
        val replies = mutableListOf<String>()
        scenario.userLines.forEach { line ->
            history += Message(sessionId = session.id, role = Message.ROLE_USER, content = line, createdAt = System.currentTimeMillis())
            val prepared = requests.prepare(
                session, history, appSettings, listenOnly = scenario.mode == "listen", zone = zone, prefix = prefix,
                sampleValues = scenario.sampleValues.takeIf { mode == Mode.ONBOARDING },
            )
            val primary = prepared.attempt.let { a -> if (otherModel == null) a else a.copy(request = a.request.copy(model = otherModel)) }
            val fallback = requests.fallback(prepared, appSettings)
                ?.let { f -> if (otherModel == null) f else f.copy(request = f.request.copy(model = otherModel)) }
            val purpose = if (otherModel == null) "testbench" else "compare"
            // Same runner as Talk: Deep falls back to Fast silently, nothing hangs.
            val outcome = ReplyRunner(ai).run(
                primary = primary,
                fallback = fallback,
                sink = {},
                onUsage = { a, t ->
                    runUsage += BenchUsage(a.request.model, t.cacheHitTokens.toLong(), t.cacheMissTokens.toLong(), t.completionTokens.toLong())
                    usage.log(purpose, a.request.model, t, System.currentTimeMillis(), a.tier.wire)
                },
                onFailure = { f -> errors.logAttempt(purpose, f) },
            )
            val raw = when (outcome) {
                is ReplyOutcome.Complete -> outcome.raw
                is ReplyOutcome.CutOff -> outcome.visible + "\n(cut off: ran out of tokens)"
                is ReplyOutcome.Failed -> throw outcome.error
            }
            // Hidden lines are shown here on purpose: the test checks when the coach adds them.
            val visible = ReplyMarkers.parse(raw).visible
            val shown = if (raw.contains("[[") || com.umair.purpose.chat.ToolCalls.TOOL_CALL.containsMatchIn(raw)) raw.trim() else visible
            replies += shown
            history += Message(sessionId = session.id, role = Message.ROLE_ASSISTANT, content = visible, createdAt = System.currentTimeMillis())
            _state.update { s -> s.copy(runs = s.runs.mapIndexed { j, r -> if (j == index) r.copy(replies = replies.toList()) else r }) }
        }
        return replies
    }
}

@Composable
fun TestBenchScreen(onBack: () -> Unit, vm: TestBenchViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        ScreenHeader("Test bench", onBack = onBack)
        Column(Modifier.padding(horizontal = SidePadding)) {
            Meta("Each scenario runs off the record with an empty memory and sample help numbers. Nothing is saved.")
            Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val running = state.running
                if (running == null) PrimaryButton("Run all", vm::runAll, enabled = state.runs.isNotEmpty())
                else TextAction("Cancel", vm::cancel, color = Purpose.colors.danger)
                Spacer(Modifier.width(12.dp))
                Meta(
                    if (running != null) "Running ${running + 1} of ${state.runs.size}…"
                    else "${state.runs.count { it.verdict == true }} pass, ${state.runs.count { it.verdict == false }} fail",
                    Modifier.weight(1f),
                )
                TextAction("Copy report", { clipboard.setText(AnnotatedString(vm.report())) }, color = Purpose.colors.textMuted)
            }
            CompareModels(state, vm::compare)
            Hairline()
        }
        LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = SidePadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            itemsIndexed(state.runs, key = { _, r -> r.scenario.name }) { i, r ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.scenario.name, style = Purpose.type.heading, color = Purpose.colors.text)
                    Meta("Mode: ${r.scenario.mode}" + (r.scenario.step?.let { " ($it step)" } ?: "") +
                        (r.scenario.sampleValues.takeIf { it.isNotEmpty() }?.let { ", sample values: ${it.joinToString(", ")}" } ?: ""))
                    r.scenario.userLines.forEachIndexed { j, u ->
                        Text(u, style = Purpose.type.userBody, color = Purpose.colors.textMuted)
                        r.replies.getOrNull(j)?.let { reply ->
                            Text(reply, style = Purpose.type.coachBody, color = Purpose.colors.text)
                            // UPDATE-14: more than one question is the recurring problem, so it stands out.
                            val q = TestBench.questions(reply)
                            Meta("questions: $q", color = if (q > 1) Purpose.colors.danger else Purpose.colors.textMuted)
                        }
                    }
                    r.error?.let { Meta("Couldn't run: $it", color = Purpose.colors.danger) }
                    Meta("Expect: ${r.scenario.expect}", color = Purpose.colors.accent)
                    if (r.replies.size == r.scenario.userLines.size) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextAction("Pass", { vm.judge(i, true) }, color = if (r.verdict == true) Purpose.colors.accent else Purpose.colors.textMuted)
                            TextAction("Fail", { vm.judge(i, false) }, color = if (r.verdict == false) Purpose.colors.danger else Purpose.colors.textMuted)
                        }
                    }
                }
            }
        }
    }
}

/** UPDATE-17 "Compare models": another model's name and prices, and the cost per run side by side. */
@Composable
private fun CompareModels(state: TestBenchState, onCompare: (String, Prices) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var model by rememberSaveable { mutableStateOf("") }
    var hit by rememberSaveable { mutableStateOf("") }
    var miss by rememberSaveable { mutableStateOf("") }
    var out by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.ownCost?.let { Meta("Your models: ${usd(it)} per run") }
        val compared = state.comparedModel
        state.compareCost?.let { if (compared != null) Meta("$compared: ${usd(it)} per run", color = Purpose.colors.accent) }
        state.runModel?.let { if (state.running != null) Meta("Running on $it…") }
        if (!open) {
            TextAction("Compare models", { open = true }, color = Purpose.colors.textMuted)
        } else {
            Meta("Runs every scenario on another model of the same provider, for both tiers. Prices are USD per 1M tokens. Judge the replies, then compare the cost.")
            LabeledField("Model name") { PurposeTextField(model, { model = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { PriceField("Cache hit", hit) { hit = it } }
                Box(Modifier.weight(1f)) { PriceField("Cache miss", miss) { miss = it } }
                Box(Modifier.weight(1f)) { PriceField("Output", out) { out = it } }
            }
            val prices = Prices(hit.toDoubleOrNull() ?: 0.0, miss.toDoubleOrNull() ?: 0.0, out.toDoubleOrNull() ?: 0.0)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction("Run on this model", { onCompare(model, prices) }, enabled = state.running == null && model.isNotBlank() && prices.known)
                TextAction("Close", { open = false }, color = Purpose.colors.textMuted)
            }
        }
    }
}

@Composable
private fun PriceField(label: String, value: String, onChange: (String) -> Unit) {
    LabeledField(label) {
        PurposeTextField(value, onChange, singleLine = true, keyboardType = KeyboardType.Decimal, modifier = Modifier.fillMaxWidth())
    }
}

private fun usd(c: CostEstimate): String =
    "$" + String.format(java.util.Locale.US, "%.4f", c.usd) + if (c.complete) "" else " (some prices unknown)"
