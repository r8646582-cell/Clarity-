package com.umair.purpose.ui.onboarding

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.umair.purpose.data.repo.OnboardingRepository
import com.umair.purpose.data.repo.OnboardingState
import com.umair.purpose.memory.BigFive
import com.umair.purpose.memory.BigFiveDraft
import com.umair.purpose.memory.ValuesDraft
import com.umair.purpose.memory.OnboardingSteps
import com.umair.purpose.memory.VALUE_CHOICES
import com.umair.purpose.ui.common.IconAction
import com.umair.purpose.ui.common.Meta
import com.umair.purpose.ui.common.PrimaryButton
import com.umair.purpose.ui.common.Ridgeline
import com.umair.purpose.ui.common.ScaleRow
import com.umair.purpose.ui.common.SidePadding
import com.umair.purpose.ui.common.TextAction
import com.umair.purpose.ui.theme.Purpose
import com.umair.purpose.ui.theme.PurposeIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class OnboardingViewModel @Inject constructor(private val repo: OnboardingRepository) : ViewModel() {
    val state: StateFlow<OnboardingState?> = repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Work in progress, read once so he picks up exactly where he left off. */
    val valuesDraft = MutableStateFlow<ValuesDraft?>(null)
    val bigFiveDraft = MutableStateFlow<BigFiveDraft?>(null)

    init {
        viewModelScope.launch {
            valuesDraft.value = repo.valuesDraft()
            bigFiveDraft.value = repo.bigFiveDraft()
        }
    }

    fun saveValuesDraft(d: ValuesDraft) = launch { repo.saveValuesDraft(d) }
    fun saveBigFiveDraft(d: BigFiveDraft) = launch { repo.saveBigFiveDraft(d) }

    fun begin() = launch { repo.markWelcomeSeen(now()) }
    fun saveValues(values: List<String>) = launch { repo.saveValues(values, now()) }
    fun saveBigFive(answers: List<Int>) = launch { repo.saveBigFive(answers, now()) }
    /** [then] runs once the choice is saved, so leaving the screen can never beat the write (Talk would reopen it). */
    fun skip(step: String, then: () -> Unit = {}) = launch {
        if (step == OnboardingSteps.WELCOME) repo.markWelcomeSeen(now()) else repo.skip(step, now())
        then()
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun now() = System.currentTimeMillis()
}

private enum class Stage { WELCOME, VALUES, BIG_FIVE, DONE }

/** Welcome, values sort, Big Five. All skippable and resumable; the five conversations follow on Talk. */
@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val valuesDraft by vm.valuesDraft.collectAsStateWithLifecycle()
    val bigFiveDraft by vm.bigFiveDraft.collectAsStateWithLifecycle()
    val s = state ?: return
    val stage = when {
        !s.welcomeSeen -> Stage.WELCOME
        !s.valuesDone -> Stage.VALUES
        !s.bigFiveDone -> Stage.BIG_FIVE
        else -> Stage.DONE
    }
    LaunchedEffect(stage) { if (stage == Stage.DONE) onDone() }

    Box(Modifier.fillMaxSize().background(Purpose.colors.background).statusBarsPadding().navigationBarsPadding()) {
        when (stage) {
            Stage.WELCOME -> Welcome(onBegin = vm::begin, onLater = { vm.skip(OnboardingSteps.WELCOME) { onDone() } })
            Stage.VALUES -> valuesDraft?.let { draft ->
                ValuesSort(draft, onDraft = vm::saveValuesDraft, onSave = vm::saveValues, onSkip = { vm.skip(OnboardingSteps.VALUES_SORT) })
            }
            Stage.BIG_FIVE -> bigFiveDraft?.let { draft ->
                BigFiveTest(draft, onDraft = vm::saveBigFiveDraft, onSave = vm::saveBigFive, onSkip = { vm.skip(OnboardingSteps.BIG_FIVE) })
            }
            Stage.DONE -> Unit
        }
    }
}

@Composable
private fun Welcome(onBegin: () -> Unit, onLater: () -> Unit) {
    // Back means "Not now": otherwise Talk would open the welcome again straight away.
    BackHandler(onBack = onLater)
    Column(Modifier.fillMaxSize().padding(horizontal = SidePadding), verticalArrangement = Arrangement.Center) {
        Ridgeline()
        Spacer(Modifier.height(32.dp))
        Text("Let's get to know each other.", style = Purpose.type.openingLine, color = Purpose.colors.text)
        Spacer(Modifier.height(12.dp))
        Meta("A few short steps over your first week. You can skip anything.")
        Spacer(Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Begin", onBegin)
            Spacer(Modifier.width(8.dp))
            TextAction("Not now", onLater, color = Purpose.colors.textMuted)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValuesSort(draft: ValuesDraft, onDraft: (ValuesDraft) -> Unit, onSave: (List<String>) -> Unit, onSkip: () -> Unit) {
    val picked = remember { mutableStateListOf(*draft.picked.filter { it in VALUE_CHOICES }.distinct().take(5).toTypedArray()) }
    var ordering by remember { mutableStateOf(draft.ordering && picked.size == 5) }
    fun saveDraft() = onDraft(ValuesDraft(picked.toList(), ordering))

    // Back from ordering returns to picking; nothing is lost either way.
    BackHandler(enabled = ordering) { ordering = false; saveDraft() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = SidePadding, vertical = 24.dp)) {
        if (!ordering) {
            Text("Pick your top 5", style = Purpose.type.screenTitle, color = Purpose.colors.text)
            Meta("The values that matter most to you, not the ones you think should.", Modifier.padding(top = 8.dp, bottom = 24.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VALUE_CHOICES.forEach { v ->
                    val on = v in picked
                    Text(
                        v,
                        style = Purpose.type.label,
                        color = if (on) Purpose.colors.accent else Purpose.colors.text,
                        modifier = Modifier.clip(CircleShape).background(Purpose.colors.surface)
                            .border(1.dp, if (on) Purpose.colors.accent else Purpose.colors.surface, CircleShape)
                            .clickable {
                                if (on) picked.remove(v) else if (picked.size < 5) picked.add(v)
                                saveDraft()
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Next", { ordering = true; saveDraft() }, enabled = picked.size == 5)
                Spacer(Modifier.width(8.dp))
                Meta("${picked.size} of 5", Modifier.weight(1f))
                TextAction("Skip", onSkip, color = Purpose.colors.textMuted)
            }
        } else {
            Text("Put them in order", style = Purpose.type.screenTitle, color = Purpose.colors.text)
            Meta("Most important at the top. Drag, or use the arrows.", Modifier.padding(top = 8.dp, bottom = 24.dp))
            ReorderList(picked, onChanged = ::saveDraft)
            Spacer(Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Save", { onSave(picked.toList()) })
                Spacer(Modifier.weight(1f))
                TextAction("Back", { ordering = false; saveDraft() }, color = Purpose.colors.textMuted)
            }
        }
    }
}

/**
 * Drag a row by its handle; it swaps with a neighbour once it passes half a row. Up/down arrows always work too.
 *
 * Each row is keyed by its value, so a row keeps its gesture while it moves. (Without the key, a swap handed
 * the dragged row's slot to another value, which restarted the gesture and froze the drag.)
 */
@Composable
private fun ReorderList(items: MutableList<String>, onChanged: () -> Unit) {
    val rowHeight = 56.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    var dragged by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val changed by rememberUpdatedState(onChanged)

    fun move(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices || from == to) return
        items.add(to, items.removeAt(from))
    }

    Column {
        items.toList().forEachIndexed { index, value ->
            key(value) {
                val isDragged = value == dragged
                Row(
                    Modifier.fillMaxWidth().height(rowHeight)
                        .zIndex(if (isDragged) 1f else 0f)
                        .offset { IntOffset(0, if (isDragged) offset.roundToInt() else 0) }
                        .background(if (isDragged) Purpose.colors.surface else Purpose.colors.background),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${index + 1}", style = Purpose.type.meta, color = Purpose.colors.textMuted, modifier = Modifier.width(28.dp))
                    Text(value, style = Purpose.type.itemText, color = Purpose.colors.text, modifier = Modifier.weight(1f))
                    IconAction(PurposeIcons.ChevronUp, "Move $value up", { move(index, index - 1); changed() }, enabled = index > 0)
                    IconAction(PurposeIcons.Chevron, "Move $value down", { move(index, index + 1); changed() }, enabled = index < items.lastIndex)
                    Icon(
                        PurposeIcons.Drag,
                        contentDescription = "Drag to reorder",
                        tint = Purpose.colors.textMuted,
                        modifier = Modifier.padding(12.dp).pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { dragged = value; offset = 0f },
                                onDragEnd = { dragged = null; offset = 0f; changed() },
                                onDragCancel = { dragged = null; offset = 0f; changed() },
                            ) { change, amount ->
                                change.consume()
                                offset += amount.y
                                val from = items.indexOf(value)
                                if (from < 0) return@detectDragGestures
                                if (offset > rowPx / 2 && from < items.lastIndex) {
                                    move(from, from + 1); offset -= rowPx
                                } else if (offset < -rowPx / 2 && from > 0) {
                                    move(from, from - 1); offset += rowPx
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BigFiveTest(draft: BigFiveDraft, onDraft: (BigFiveDraft) -> Unit, onSave: (List<Int>) -> Unit, onSkip: () -> Unit) {
    val items = BigFive.ITEMS
    val perPage = 5
    val pages = (items.size + perPage - 1) / perPage
    val answers = remember {
        mutableStateListOf(*Array(items.size) { i -> draft.answers.getOrNull(i)?.takeIf { it in 1..5 } ?: 0 })
    }
    var page by remember { mutableIntStateOf(draft.page.coerceIn(0, pages - 1)) }
    val scroll = rememberScrollState()
    LaunchedEffect(page) { scroll.scrollTo(0) }
    fun saveDraft() = onDraft(BigFiveDraft(answers.toList(), page, BigFive.ITEM_SET))
    BackHandler(enabled = page > 0) { page--; saveDraft() }

    Column(Modifier.fillMaxSize()) {
        // Thin progress line.
        Box(Modifier.fillMaxWidth().height(2.dp).background(Purpose.colors.hairline)) {
            Box(Modifier.fillMaxWidth((page + 1f) / pages).height(2.dp).background(Purpose.colors.accent))
        }
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = SidePadding, vertical = 24.dp)) {
            if (page == 0) {
                Text("How you tend to be", style = Purpose.type.screenTitle, color = Purpose.colors.text)
                Meta(
                    "A reflection tool, not a diagnosis. Answer as you are, not as you'd like to be.",
                    Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
            }
            Meta("${page + 1} of $pages", Modifier.padding(bottom = 16.dp))
            (page * perPage until minOf(items.size, (page + 1) * perPage)).forEach { i ->
                Text("I " + items[i].text.replaceFirstChar { it.lowercase() }, style = Purpose.type.itemText, color = Purpose.colors.text)
                Spacer(Modifier.height(8.dp))
                ScaleRow(answers[i].takeIf { it > 0 }, { answers[i] = it; saveDraft() }, "Disagree", "Agree")
                Spacer(Modifier.height(24.dp))
            }
            val pageDone = (page * perPage until minOf(items.size, (page + 1) * perPage)).all { answers[it] > 0 }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (page < pages - 1) PrimaryButton("Next", { page++; saveDraft() }, enabled = pageDone)
                else PrimaryButton("Finish", { onSave(answers.toList()) }, enabled = pageDone)
                Spacer(Modifier.weight(1f))
                if (page > 0) TextAction("Back", { page--; saveDraft() }, color = Purpose.colors.textMuted)
                TextAction("Skip", onSkip, color = Purpose.colors.textMuted)
            }
        }
    }
}
