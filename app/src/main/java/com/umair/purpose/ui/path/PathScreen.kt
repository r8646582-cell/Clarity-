package com.umair.purpose.ui.path

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.repo.JourneyRepository
import com.umair.purpose.journey.JourneyPlan
import com.umair.purpose.journey.JourneyRules
import com.umair.purpose.promise.Due
import com.umair.purpose.promise.due
import com.umair.purpose.ui.common.DotRow
import com.umair.purpose.ui.common.EditField
import com.umair.purpose.ui.common.EditSheet
import com.umair.purpose.ui.common.Hairline
import com.umair.purpose.ui.common.Heading
import com.umair.purpose.ui.common.EmptyRidge
import com.umair.purpose.ui.common.Meta
import com.umair.purpose.ui.common.PurposeSheet
import com.umair.purpose.ui.common.ScreenHeader
import com.umair.purpose.ui.common.SidePadding
import com.umair.purpose.ui.common.TextAction
import com.umair.purpose.ui.common.rememberClickHaptic
import com.umair.purpose.ui.theme.Purpose
import com.umair.purpose.ui.theme.PurposeIcons
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.runtime.saveable.rememberSaveable
import com.umair.purpose.ui.common.Block
import com.umair.purpose.ui.common.PurposeTextField
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import com.umair.purpose.journey.Suggestion
import com.umair.purpose.journey.JourneyGroups
import com.umair.purpose.journey.JourneyDesigner
import com.umair.purpose.journey.JourneyDesign
import com.umair.purpose.journey.CustomDraft
import com.umair.purpose.ui.common.LocalSnackbar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val TIME = DateTimeFormatter.ofPattern("h:mm a")

/** "Tonight at 9:00 pm", "Tomorrow", "Was due Mon 3 Oct". */
fun dueLabel(d: Due, today: LocalDate = LocalDate.now()): String {
    val at = d.time?.let { " at " + it.format(TIME).lowercase() } ?: ""
    return when {
        d.date.isBefore(today) -> "Was due ${d.date.format(DAY)}$at"
        d.date == today -> (if ((d.time?.hour ?: 0) >= 17) "Tonight" else "Today") + at
        d.date == today.plusDays(1) -> "Tomorrow$at"
        else -> d.date.format(DAY) + at
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PathScreen(onOpenTalk: () -> Unit, onOpenTree: () -> Unit, vm: PathViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val past = vm.pastPages.collectAsLazyPagingItems()
    var selected by remember { mutableStateOf<Promise?>(null) }
    var editing by remember { mutableStateOf<Promise?>(null) }
    var showPast by remember { mutableStateOf(false) }
    var pickJourney by remember { mutableStateOf(false) }
    val snackbar = LocalSnackbar.current
    val click = rememberClickHaptic()
    val scope = rememberCoroutineScope()
    val today = state.today

    // DESIGN.md "Small feedback": swipe actions can be undone for 5 seconds.
    fun withUndo(p: Promise, message: String, action: () -> Unit) {
        action()
        snackbar(message, "Undo") { vm.undo(p) }
    }

    Box(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Path")
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = SidePadding)) {
                // UPDATE-18: the growth tree, small, at the top; tapping opens it full screen.
                item { TreePreview(tree, onOpenTree) }
                item {
                    JourneySection(
                        state, onStartStep = { vm.startTodaysStep(onOpenTalk) }, onMarkDone = vm::markTodaysStepDone, onStop = vm::stopJourney,
                        onPick = { pickJourney = true }, onResume = vm::resumeJourney,
                    )
                }

                item { Heading("Promises") }
                if (state.recordLine != null) {
                    item {
                        Text(state.recordLine!!, style = Purpose.type.itemText, color = Purpose.colors.text)
                        state.runLine?.let { Meta(it) }
                        Spacer(Modifier.height(16.dp))
                    }
                }
                if (state.loaded && state.open.isEmpty()) {
                    item {
                        EmptyRidge(Modifier.padding(top = 16.dp))
                        Text(
                            "When we decide on something for you to do, it'll show up here so we can keep track together.",
                            style = Purpose.type.itemText.copy(fontSize = Purpose.type.heading.fontSize * 0.9f),
                            color = Purpose.colors.textMuted,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                items(state.open, key = { "o${it.id}" }) { p ->
                    val currentPromise by androidx.compose.runtime.rememberUpdatedState(p)
                    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                        when (v) {
                            SwipeToDismissBoxValue.StartToEnd -> withUndo(currentPromise, "Marked as kept") { click(); vm.markKept(currentPromise.id) }
                            SwipeToDismissBoxValue.EndToStart -> withUndo(currentPromise, "Let go") { click(); vm.letGo(currentPromise.id) }
                            SwipeToDismissBoxValue.Settled -> Unit
                        }
                        v != SwipeToDismissBoxValue.Settled
                    })
                    SwipeToDismissBox(state = swipe, backgroundContent = {}) {
                        OpenPromiseRow(p, today) { selected = p }
                    }
                }
                item {
                    Hairline(Modifier.padding(top = 8.dp))
                    Row(
                        Modifier.fillMaxWidth().clickable { showPast = !showPast }.padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Past promises", style = Purpose.type.label, color = Purpose.colors.text)
                        Spacer(Modifier.width(6.dp))
                        Icon(PurposeIcons.Chevron, contentDescription = null, tint = Purpose.colors.textMuted, modifier = Modifier.size(16.dp))
                    }
                }
                if (showPast) {
                    if (past.itemCount == 0 && past.loadState.refresh !is androidx.paging.LoadState.Loading) item { Meta("Nothing here yet.") }
                    items(count = past.itemCount, key = past.itemKey { "p${it.id}" }) { i -> past[i]?.let { PastPromiseRow(it) } }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }

    selected?.let { p ->
        PurposeSheet(onDismiss = { selected = null }) {
            Text(p.text, style = Purpose.type.itemText, color = Purpose.colors.text)
            p.why?.let { Meta(it, Modifier.padding(top = 6.dp)) }
            p.due?.let { Meta(dueLabel(it, today), Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(16.dp))
            TextAction("I kept it", { click(); vm.markKept(p.id); selected = null })
            TextAction("Change it", { editing = p; selected = null }, color = Purpose.colors.text)
            TextAction("Let it go", { vm.letGo(p.id); selected = null }, color = Purpose.colors.text)
            if (p.remindAt != null) TextAction("Remove reminder", { vm.removeReminder(p.id); selected = null }, color = Purpose.colors.textMuted)
        }
    }
    editing?.let { p -> EditPromiseSheet(p, onDismiss = { editing = null }) { text, due -> vm.edit(p.id, text, due); editing = null } }
    if (pickJourney) JourneyListSheet(onDismiss = { pickJourney = false }) { name -> pickJourney = false; vm.startJourney(name, onOpenTalk) }
}

@Composable
private fun TreePreview(g: com.umair.purpose.growth.TreeGeometry?, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(Purpose.colors.surface).clickable(onClick = onOpen).padding(12.dp)
    ) {
        Box(Modifier.fillMaxWidth().height(180.dp)) {
            if (g != null) com.umair.purpose.ui.growth.GrowthTreeCanvas(g, Modifier.fillMaxSize())
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Meta(
                when {
                    g == null -> ""
                    g.leafCount == 0 -> "Your tree: just its roots so far"
                    else -> "${g.leafCount} ${if (g.leafCount == 1) "leaf" else "leaves"}, ${g.branchCount} ${if (g.branchCount == 1) "branch" else "branches"}"
                },
                Modifier.weight(1f),
            )
            Meta("Open")
        }
    }
}

@Composable
private fun JourneySection(state: PathUiState, onStartStep: () -> Unit, onMarkDone: () -> Unit, onStop: () -> Unit, onPick: () -> Unit, onResume: () -> Unit) {
    val j = state.journey
    val plan = state.plan
    if (j == null || plan == null) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onPick).padding(vertical = 12.dp)) {
            Text("Start a journey", style = Purpose.type.itemText, color = Purpose.colors.text)
            Meta("A few days of focused work on one thing.")
        }
        return
    }
    val done = JourneyRules.daysDone(j)
    val day = j.currentDay.coerceAtMost(j.totalDays)
    val paused = j.status == com.umair.purpose.data.db.Journey.PAUSED
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(j.name, style = Purpose.type.heading, color = Purpose.colors.text)
        Meta(if (paused) "Paused on day $day of ${j.totalDays}" else "Day $day of ${j.totalDays}")
        if (paused) {
            DotRow(total = j.totalDays, filled = done, outlined = null, modifier = Modifier.padding(vertical = 12.dp))
            state.adjustment?.let { Meta(it, Modifier.padding(bottom = 4.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction("Resume", onResume)
                Spacer(Modifier.weight(1f))
                TextAction("Stop journey", onStop, color = Purpose.colors.textMuted)
            }
            return@Column
        }
        DotRow(total = j.totalDays, filled = done, outlined = if (state.stepToday) j.currentDay - 1 else null, modifier = Modifier.padding(vertical = 12.dp))
        plan.step(day)?.let { step ->
            Text(if (state.stepToday) step.theme else "Today's step is done. Next: ${plan.step(j.currentDay)?.theme ?: step.theme}",
                style = Purpose.type.itemText, color = Purpose.colors.text)
        }
        // UPDATE-18: why the plan changed, in his words' register.
        state.adjustment?.let { Meta(it, Modifier.padding(top = 4.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.stepToday) {
                TextAction("Start today's step", onStartStep)
                Spacer(Modifier.width(16.dp))
                TextAction("I did it", onMarkDone, color = Purpose.colors.textMuted)
            }
            Spacer(Modifier.weight(1f))
            TextAction("Stop journey", onStop, color = Purpose.colors.textMuted)
        }
    }
}

@Composable
private fun OpenPromiseRow(p: Promise, today: LocalDate, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Purpose.colors.background).clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(p.text, style = Purpose.type.itemText, color = Purpose.colors.text)
        val due = p.due
        if (due != null || p.remindAt != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                due?.let { Meta(dueLabel(it, today), color = if (it.date == today) Purpose.colors.accent else Purpose.colors.textMuted) }
                if (p.remindAt != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(PurposeIcons.Bell, contentDescription = "Reminder set", tint = Purpose.colors.textMuted, modifier = Modifier.size(14.dp))
                }
            }
        }
        p.why?.let { Meta(it, Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun PastPromiseRow(p: Promise) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(22.dp).padding(top = 4.dp)) {
            if (p.status == Promise.KEPT) Icon(PurposeIcons.Check, contentDescription = "Kept", tint = Purpose.colors.accent, modifier = Modifier.size(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(p.text, style = Purpose.type.itemText, color = Purpose.colors.text)
            Meta(
                when (p.status) {
                    Promise.KEPT -> "Kept"
                    Promise.BROKEN -> "Didn't happen"
                    Promise.RENEGOTIATED -> "Changed"
                    else -> "Let go"
                }
            )
            p.lesson?.let { Meta((if (p.status == Promise.KEPT) "What worked: " else "What we learned: ") + it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPromiseSheet(p: Promise, onDismiss: () -> Unit, onSave: (String, Due?) -> Unit) {
    var due by remember(p.id, p.dueAt) { mutableStateOf(p.due) }
    var pickingTime by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    EditSheet(
        title = "Change it",
        fields = listOf(EditField("Promise", p.text, singleLine = false)),
        onDismiss = onDismiss,
        onSave = { onSave(it[0], due) },
        extra = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Meta(due?.let { dueLabel(it) } ?: "No date", Modifier.weight(1f))
                TextAction("Pick date", { picking = true })
                TextAction("Time", { pickingTime = true })
                if (due != null) TextAction("Clear", { due = null }, color = Purpose.colors.textMuted)
            }
        },
    )
    if (pickingTime) {
        val time = rememberTimePickerState(initialHour = due?.time?.hour ?: 9, initialMinute = due?.time?.minute ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            containerColor = Purpose.colors.surface,
            tonalElevation = 0.dp,
            title = { Text("Due time", style = Purpose.type.heading, color = Purpose.colors.text) },
            text = { TimeInput(state = time) },
            confirmButton = { TextAction("Save", {
                due = Due(due?.date ?: LocalDate.now(), java.time.LocalTime.of(time.hour, time.minute))
                pickingTime = false
            }) },
            dismissButton = { TextAction("Cancel", { pickingTime = false }) },
        )
    }
    if (picking) {
        // The picker works in UTC midnight millis.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (due?.date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextAction("OK", {
                    pickerState.selectedDateMillis?.let {
                        due = Due(Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate(), due?.time)
                    }
                    picking = false
                })
            },
            dismissButton = { TextAction("Cancel", { picking = false }, color = Purpose.colors.textMuted) },
        ) { DatePicker(state = pickerState) }
    }
}

sealed interface DesignState {
    data object Idle : DesignState
    data object Working : DesignState
    data class Ready(val draft: CustomDraft) : DesignState
    data class Failed(val message: String) : DesignState
}

data class JourneyListState(val groups: JourneyGroups? = null, val design: DesignState = DesignState.Idle)

@HiltViewModel
class JourneyListViewModel @Inject constructor(
    private val journeys: JourneyRepository,
    private val designer: JourneyDesigner,
    private val errors: com.umair.purpose.dev.ErrorLogger,
    secrets: com.umair.purpose.security.SecretStore,
) : ViewModel() {
    private val plans = MutableStateFlow<List<JourneyPlan>>(emptyList())
    private val suggestions = MutableStateFlow<List<Suggestion>>(emptyList())
    private val design = MutableStateFlow<DesignState>(DesignState.Idle)

    val state: StateFlow<JourneyListState> = combine(plans, journeys.observeAll(), suggestions, design) { p, all, sug, d ->
        JourneyListState(if (p.isEmpty()) null else JourneyDesign.groups(p, all, sug), d)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JourneyListState())

    init {
        viewModelScope.launch { plans.value = journeys.catalog() }
        // Suggestions: cached for a week; asking never blocks the list.
        if (secrets.hasApiKey.value) viewModelScope.launch {
            runCatching { designer.suggest() }.onSuccess { suggestions.value = it }
                .onFailure { if (it is CancellationException) throw it else errors.log("journey", it) }
        }
    }

    fun design(request: String) {
        if (design.value == DesignState.Working) return
        design.value = DesignState.Working
        viewModelScope.launch {
            design.value = try {
                DesignState.Ready(designer.design(request))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors.log("journey", e)
                DesignState.Failed("Couldn't make one just now. Try again in a moment.")
            }
        }
    }

    fun saveAndStart(d: CustomDraft, onPick: (String) -> Unit) {
        viewModelScope.launch {
            val plan = journeys.saveCustom(d, System.currentTimeMillis())
            plans.value = journeys.catalog()
            design.value = DesignState.Idle
            onPick(plan.name)
        }
    }
}

/** The journeys from journeys.md: name, one-line description, length. */
@Composable
fun JourneyListSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val vm: JourneyListViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var request by rememberSaveable { mutableStateOf("") }
    PurposeSheet(onDismiss) {
        Text("Start a journey", style = Purpose.type.heading, color = Purpose.colors.text, modifier = Modifier.padding(bottom = 8.dp))
        Meta("One step a day. Starting one ends any other.", Modifier.padding(bottom = 8.dp))
        val g = state.groups
        // A plain column: the sheet itself scrolls (a lazy list can't sit inside it).
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (g != null && g.suggested.isNotEmpty()) {
                SheetGroup("Suggested for you")
                g.suggested.forEach { (plan, why) -> JourneyRow(plan.name, why.ifBlank { plan.blurb }, "${plan.days} days") { onPick(plan.name) } }
            }

            SheetGroup("Make one for me")
            when (val d = state.design) {
                DesignState.Idle, is DesignState.Failed -> {
                    Meta("Seven days built around you. Say what it's for, or leave it blank.")
                    PurposeTextField(
                        request, { request = it.take(120) }, placeholder = "e.g. studying when home is noisy",
                        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    if (d is DesignState.Failed) Meta(d.message, color = Purpose.colors.danger)
                    TextAction("Make one for me", { vm.design(request) })
                }
                DesignState.Working -> Meta("Designing your journey… this can take a minute.", Modifier.padding(vertical = 8.dp))
                is DesignState.Ready -> {
                    Block {
                        Text(d.draft.name, style = Purpose.type.itemText, color = Purpose.colors.text)
                        if (d.draft.why.isNotBlank()) Meta(d.draft.why)
                        Spacer(Modifier.height(8.dp))
                        d.draft.steps.forEach { st -> Meta("Day ${st.day}: ${st.theme}") }
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextAction("Start", { vm.saveAndStart(d.draft, onPick) })
                            TextAction("Try another", { vm.design(request) }, color = Purpose.colors.textMuted)
                        }
                    }
                }
            }

            if (g != null && g.yours.isNotEmpty()) {
                SheetGroup("Yours")
                g.yours.forEach { plan -> JourneyRow(plan.name, plan.why ?: plan.blurb, "${plan.days} days") { onPick(plan.name) } }
            }
            SheetGroup("All journeys")
            g?.all?.forEach { plan -> JourneyRow(plan.name, plan.blurb, "${plan.days} days") { onPick(plan.name) } }
            if (g != null && g.completed.isNotEmpty()) {
                SheetGroup("Completed")
                g.completed.forEach { (j, plan) ->
                    val date = j.completedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")) }
                    JourneyRow(
                        j.name,
                        j.takeaway ?: plan?.blurb.orEmpty(),
                        listOfNotNull("Done", date).joinToString(", ") + if (plan != null) " · tap to restart" else "",
                    ) { if (plan != null) onPick(j.name) }
                }
            }
        }
    }
}

@Composable
private fun SheetGroup(title: String) {
    Text(title, style = Purpose.type.label, color = Purpose.colors.textMuted, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun JourneyRow(name: String, line: String, meta: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(name, style = Purpose.type.itemText, color = Purpose.colors.text)
        if (line.isNotBlank()) Meta(line)
        Meta(meta)
    }
}
