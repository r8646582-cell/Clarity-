package com.umair.purpose.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.umair.purpose.data.db.Promise
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.Session
import com.umair.purpose.data.repo.ChatRepository
import com.umair.purpose.data.repo.MemoryRepository
import com.umair.purpose.data.repo.PromptRepository
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UiPrefs
import com.umair.purpose.chat.ChatRequests
import com.umair.purpose.dev.CrashLog
import com.umair.purpose.dev.ErrorLogger
import com.umair.purpose.dev.HealthCheck
import com.umair.purpose.dev.HealthInputs
import com.umair.purpose.dev.HealthItem
import com.umair.purpose.dev.HealthLevel
import com.umair.purpose.data.db.Letter
import com.umair.purpose.promise.ReminderScheduler
import com.umair.purpose.system.Reliability
import com.umair.purpose.ui.common.Hairline
import com.umair.purpose.ui.common.Meta
import com.umair.purpose.ui.common.PrimaryButton
import com.umair.purpose.ui.common.ScreenHeader
import com.umair.purpose.ui.common.SidePadding
import com.umair.purpose.ui.common.TextAction
import com.umair.purpose.ui.common.ConfirmDialog
import com.umair.purpose.ui.common.LocalSnackbar
import com.umair.purpose.ui.theme.Purpose
import com.umair.purpose.ui.theme.PurposeIcons
import com.umair.purpose.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

// ---------------------------------------------------------------------------------------------------------------
// Developer menu: a normal row at the bottom of Settings > Advanced (CLAUDE.md "Developer menu (visible)").
// ---------------------------------------------------------------------------------------------------------------

@HiltViewModel
class DeveloperViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chat: ChatRepository,
    private val work: WorkScheduler,
    private val reminders: ReminderScheduler,
    private val errors: ErrorLogger,
    private val speed: com.umair.purpose.dev.SpeedTest,
    private val growth: com.umair.purpose.growth.GrowthEngine,
    private val chapters: com.umair.purpose.letter.ChapterWriter,
) : ViewModel() {
    val note = MutableStateFlow<String?>(null)
    /** UPDATE-15: the 5-year speed test's report, line by line. */
    val speedReport = MutableStateFlow<List<String>>(emptyList())
    private var speedRunning = false

    fun runSpeedTest() {
        if (speedRunning) return
        speedRunning = true
        speedReport.value = listOf("Starting…")
        viewModelScope.launch {
            try {
                speedReport.value = speed.run { p -> speedReport.value = listOf(p) }.lines
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                speedReport.value = listOf("The speed test stopped: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                speedRunning = false
            }
        }
    }

    /** UPDATE-18: the weekly growth-tree run, now (it still needs real evidence to propose anything). */
    fun runGrowthNow() {
        note.value = "Looking for growth with real evidence…"
        viewModelScope.launch {
            note.value = runCatching { growth.run(com.umair.purpose.growth.GrowthEngine.Trigger.WEEKLY) }.fold(
                { n -> if (n == 0) "Nothing proposed: no leaf has enough evidence yet (that's normal; most weeks there's none)." else "$n proposal(s) waiting on Talk." },
                { e -> "Couldn't run it: ${e.message ?: e.javaClass.simpleName}" },
            )
        }
    }

    /** UPDATE-15: any life chapter owed (a finished quarter with real conversation). */
    fun writeChaptersNow() {
        note.value = "Writing any chapter that's owed…"
        viewModelScope.launch {
            note.value = runCatching {
                val due = chapters.due(java.time.LocalDate.now())
                due.forEach { chapters.write(it) }
                due.size
            }.fold({ n -> if (n == 0) "No chapter is owed yet: one comes after each finished quarter you talked in." else "Wrote $n chapter(s). See Mirror." },
                { e -> "Couldn't write it: ${e.message ?: e.javaClass.simpleName}" })
        }
    }
    val lastCrash = MutableStateFlow(CrashLog.read(context))
    val errorLog = errors.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Ends the open conversation (if any) and reflects on everything pending, now. */
    fun runReflectionNow() {
        viewModelScope.launch {
            chat.endOpenSession(System.currentTimeMillis())
            work.reflect()
            note.value = "Reflection started. It runs in the background; What I know updates when it's done."
        }
    }

    fun writeTestLetter(monthly: Boolean) {
        work.writeTestLetter(monthly)
        note.value = if (monthly) "Writing a test monthly letter for this month so far. It appears on Mirror in a few minutes, if you talked this month."
        else "Writing a test letter for this week. It appears on Mirror in a minute or two, if there was a conversation this week."
    }

    fun fireTestReminder() {
        reminders.scheduleTest(10_000)
        note.value = "A test reminder will arrive in about 10 seconds."
    }

    fun gardenNow() {
        work.garden(now = true)
        note.value = "Memory gardening started. What I know updates when it's done."
    }

    fun clearCrash() {
        CrashLog.clear(context)
        lastCrash.value = null
    }

    fun clearErrors() {
        viewModelScope.launch { errors.clear() }
    }
}

@Composable
fun DeveloperScreen(
    onBack: () -> Unit,
    onOpenTestBench: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenErrorLog: () -> Unit,
    onOpenPrompts: () -> Unit,
    onPreviewTree: (Int) -> Unit = {},
    vm: DeveloperViewModel = hiltViewModel(),
) {
    val note by vm.note.collectAsStateWithLifecycle()
    val speedReport by vm.speedReport.collectAsStateWithLifecycle()
    val crash by vm.lastCrash.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        ScreenHeader("Developer", onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = SidePadding),
        ) {
            Meta("Tools for checking and tuning Purpose yourself. See MAINTENANCE.md.")
            Spacer(Modifier.height(16.dp))
            NavRow("Health check", "Is everything working? Check once a week.", onOpenHealth)
            NavRow("Test bench", "Run every test scenario off the record.", onOpenTestBench)
            NavRow("Compare models", "Run the test bench on another model and see the cost per run.", onOpenTestBench)
            NavRow("Prompt editor", "Read and tune the prompts without rebuilding the app.", onOpenPrompts)
            NavRow("Error log", "What went wrong lately. Never your messages.", onOpenErrorLog)
            Spacer(Modifier.height(24.dp))
            Text("Run now", style = Purpose.type.heading, color = Purpose.colors.text)
            Spacer(Modifier.height(8.dp))
            TextAction("Run reflection now", vm::runReflectionNow)
            TextAction("Write test weekly letter", { vm.writeTestLetter(monthly = false) })
            TextAction("Write test monthly letter", { vm.writeTestLetter(monthly = true) })
            TextAction("Fire test reminder", vm::fireTestReminder)
            TextAction("Run memory gardening now", vm::gardenNow)
            TextAction("Write owed chapters now", vm::writeChaptersNow)
            TextAction("Look for growth-tree proposals now", vm::runGrowthNow)
            note?.let { Meta(it, Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(24.dp))
            // UPDATE-18: judge the drawing at every size, with made-up leaves that are never saved.
            Text("Preview tree with sample leaves", style = Purpose.type.heading, color = Purpose.colors.text)
            Spacer(Modifier.height(8.dp))
            Row {
                listOf(10, 50, 300).forEach { n -> TextAction("$n leaves", { onPreviewTree(n) }) }
            }
            Spacer(Modifier.height(24.dp))
            // UPDATE-15: proof that ten years of data stays quick, in a throwaway database.
            Text("Speed with years of data", style = Purpose.type.heading, color = Purpose.colors.text)
            Spacer(Modifier.height(8.dp))
            Meta("Builds a separate test database with 5 years of made-up data, times the screens, then deletes it. Takes a minute or two. Your data is never touched.")
            TextAction("Generate 5 years of fake data", vm::runSpeedTest)
            speedReport.forEach { Meta(it, Modifier.padding(top = 2.dp)) }
            Spacer(Modifier.height(24.dp))
            Text("Last crash", style = Purpose.type.heading, color = Purpose.colors.text)
            Spacer(Modifier.height(8.dp))
            val c = crash
            if (c == null) {
                Meta("No crash recorded.")
            } else {
                Meta("Stack trace only, never your messages.")
                Text(c.take(4000), style = Purpose.type.meta, color = Purpose.colors.textMuted)
                Row {
                    TextAction("Copy", { clipboard.setText(AnnotatedString(c)) })
                    TextAction("Clear", vm::clearCrash, color = Purpose.colors.textMuted)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun NavRow(title: String, note: String, onClick: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Purpose.type.itemText, color = Purpose.colors.text)
                Meta(note)
            }
            Icon(PurposeIcons.Chevron, contentDescription = null, tint = Purpose.colors.textMuted, modifier = Modifier.size(18.dp))
        }
        Hairline()
    }
}

@Composable
fun ErrorLogScreen(onBack: () -> Unit, vm: DeveloperViewModel = hiltViewModel()) {
    val errors by vm.errorLog.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        ScreenHeader("Error log", onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = SidePadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Meta("Time, job, model, status and the provider's error text. Never your messages.")
            if (errors.isEmpty()) {
                Meta("Nothing went wrong lately.")
                return@Column
            }
            val report = errors.joinToString("\n") { e ->
                listOfNotNull(
                    java.time.Instant.ofEpochMilli(e.createdAt).toString(), e.source, e.model, e.errorType,
                    e.httpCode?.let { "HTTP $it" }, e.estimatedInputTokens?.let { "~$it input tokens" }, e.errorBody,
                ).joinToString(" | ")
            }
            Row {
                TextAction("Copy log", { clipboard.setText(AnnotatedString(report)) })
                TextAction("Clear", vm::clearErrors, color = Purpose.colors.textMuted)
            }
            errors.forEach { e ->
                Column {
                    Text(
                        listOfNotNull(
                            java.time.Instant.ofEpochMilli(e.createdAt).atZone(ZoneId.systemDefault()).toLocalDateTime().toString().take(16).replace('T', ' '),
                            e.source,
                        ).joinToString(" · "),
                        style = Purpose.type.label, color = Purpose.colors.text,
                    )
                    Meta(listOfNotNull(e.model, e.errorType, e.estimatedInputTokens?.let { "~$it tokens in" }).joinToString(" · "))
                    e.errorBody?.let { Meta(it) }
                }
                Hairline()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Health check (CLAUDE.md "Health check")
// ---------------------------------------------------------------------------------------------------------------

@HiltViewModel
class HealthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: PurposeDatabase,
    private val memory: MemoryRepository,
    private val settings: SettingsRepository,
    private val requests: ChatRequests,
    private val prompts: PromptRepository,
    private val uiPrefs: UiPrefs,
    private val work: WorkScheduler,
    private val usage: com.umair.purpose.data.repo.UsageRepository,
    private val failover: com.umair.purpose.ai.FailoverPolicy,
) : ViewModel() {
    private val _items = MutableStateFlow<List<HealthItem>>(emptyList())
    val items: StateFlow<List<HealthItem>> = _items.asStateFlow()
    val note = MutableStateFlow<String?>(null)

    fun refresh() {
        viewModelScope.launch { _items.value = runCatching { HealthCheck.items(gather()) }.getOrElse { emptyList() } }
    }

    fun cleanUp() {
        work.garden(now = true)
        note.value = "Cleaning up in the background. Check again in a few minutes."
    }

    fun report(): String = HealthCheck.report(_items.value, appVersion(context), System.currentTimeMillis(), ZoneId.systemDefault())

    private suspend fun gather(): HealthInputs {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val s = settings.get()
        val letters = db.letterDao().all()
        val notes = db.noteDao().all().filter { it.status == com.umair.purpose.data.db.Note.ACTIVE }
        val people = db.personDao().all().filter { !it.deletedByUser }
        val promises = db.promiseDao().all()
        // What really goes with each message: the context block and summaries, after the budget (UPDATE-15).
        val contextTokens = runCatching { memory.fittedContext(Session(id = 0, startedAt = now), LocalDate.now(zone), zone).tokens }.getOrDefault(0)
        val (from, to) = usage.thisMonth(zone)
        val breakdown = com.umair.purpose.cost.CostEstimator.breakdown(usage.breakdown(from, to), s.pricing)
        return HealthInputs(
            now = now,
            zone = zone,
            reflection = uiPrefs.job("reflection"),
            sessionsWaiting = db.sessionDao().unreflected().size,
            lastWeeklyLetterAt = letters.filter { it.kind == Letter.WEEKLY }.maxOfOrNull { it.createdAt },
            lastMonthlyLetterAt = letters.filter { it.kind == Letter.MONTHLY }.maxOfOrNull { it.createdAt },
            letterDelayed = uiPrefs.letterDelayed.value,
            gardening = uiPrefs.job("gardening"),
            pendingReminders = promises.count { it.status == Promise.OPEN && (it.remindAt ?: 0) > now },
            exactAlarmsAllowed = ReminderScheduler.canScheduleExactAlarms(context),
            notificationsAllowed = ReminderScheduler.canNotify(context),
            batteryUnrestricted = Reliability.batteryUnrestricted(context),
            xiaomi = Reliability.isXiaomi(),
            failedJobsLast7Days = db.errorDao().jobFailuresSince(now - 7L * 24 * 3600 * 1000),
            notes = notes.size,
            people = people.size,
            events = db.behaviorDao().count(),
            quotes = db.quoteDao().count(),
            possibleDuplicates = HealthCheck.duplicates(notes.map { it.text }) + HealthCheck.duplicates(people.map { it.name }),
            contextTokens = contextTokens,
            monthCostUsd = requests.monthCost(s),
            budgetUsd = s.monthlyBudget,
            autoBackup = s.autoBackup,
            lastBackupAt = uiPrefs.lastBackupAt.takeIf { it > 0 },
            promptOverrides = db.promptDao().all().map { it.name },
            promptsBuiltInChanged = prompts.builtInChangedSinceEdit().sorted(),
            archived = db.noteDao().all().count { it.status == com.umair.purpose.data.db.Note.RETIRED },
            chapters = db.chapterDao().all().size,
            lastRestoreTestAt = s.lastRestoreTestAt,
            keystoreConfirmedAt = s.keystoreConfirmedAt,
            providerKeyExpiry = s.providerKeyExpiry,
            backupProviderSet = s.backupProvider != null,
            usingBackupProvider = failover.usingBackup.value,
            queuedMessages = db.messageDao().queuedCount(),
            cacheHitRate = breakdown.cacheHitRate,
            deepShare = breakdown.deepShare,
        )
    }
}

@Composable
fun HealthCheckScreen(onBack: () -> Unit, onOpenErrorLog: () -> Unit, onOpenSettings: () -> Unit, vm: HealthViewModel = hiltViewModel()) {
    val items by vm.items.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val snackbar = LocalSnackbar.current
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        ScreenHeader("Health check", onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = SidePadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val reds = items.count { it.level == HealthLevel.RED }
                val ambers = items.count { it.level == HealthLevel.AMBER }
                Meta(
                    when {
                        items.isEmpty() -> "Checking…"
                        reds > 0 -> "$reds to fix, $ambers to look at."
                        ambers > 0 -> "All working; $ambers to look at."
                        else -> "Everything is green."
                    },
                    Modifier.weight(1f),
                )
                TextAction("Copy health report", {
                    clipboard.setText(AnnotatedString(vm.report()))
                    snackbar("Copied")
                })
            }
            note?.let { Meta(it) }
            Spacer(Modifier.height(8.dp))
            items.forEach { item ->
                val onClick: (() -> Unit)? = when (item.action) {
                    HealthItem.Action.OPEN_ERROR_LOG -> onOpenErrorLog
                    HealthItem.Action.OPEN_RELIABILITY -> onOpenSettings
                    HealthItem.Action.CLEAN_UP, null -> null
                }
                Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Dot(item.level)
                        Spacer(Modifier.width(10.dp))
                        Text(item.title, style = Purpose.type.label, color = Purpose.colors.text)
                    }
                    Text(item.sentence, style = Purpose.type.userBody, color = Purpose.colors.textMuted, modifier = Modifier.padding(start = 20.dp, top = 2.dp))
                    if (item.action == HealthItem.Action.CLEAN_UP) {
                        TextAction("Clean up now", vm::cleanUp, Modifier.padding(start = 12.dp))
                    }
                }
                Hairline()
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Dot(level: HealthLevel) {
    val night = Purpose.colors.isNight
    val color = when (level) {
        HealthLevel.GREEN -> if (night) Color(0xFF8DBF8A) else Color(0xFF3E7B3B)
        HealthLevel.AMBER -> Purpose.colors.accent
        HealthLevel.RED -> Purpose.colors.danger
    }
    Spacer(Modifier.size(10.dp).clip(CircleShape).background(color))
}

// ---------------------------------------------------------------------------------------------------------------
// Prompt editor (CLAUDE.md "Prompt editor")
// ---------------------------------------------------------------------------------------------------------------

/** [builtInChanged]: an app update changed the built-in version since he saved his (UPDATE-13). */
data class PromptRow(val name: String, val edited: Boolean, val builtInChanged: Boolean = false)

@HiltViewModel
class PromptListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prompts: PromptRepository,
) : ViewModel() {
    private val _rows = MutableStateFlow<List<PromptRow>>(emptyList())
    val rows: StateFlow<List<PromptRow>> = _rows.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val edited = prompts.editedNames()
            val changed = prompts.builtInChangedSinceEdit()
            _rows.value = prompts.names().map { PromptRow(it, it in edited, it in changed) }
        }
    }

    /** Shares every prompt, as used now, as one text file. */
    fun exportAll() {
        viewModelScope.launch {
            val text = prompts.exportAll()
            val dir = java.io.File(context.cacheDir, "export").apply { mkdirs() }
            val file = java.io.File(dir, "purpose-prompts-${LocalDate.now()}.txt").apply { writeText(text) }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, file.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, "Export all prompts").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

@Composable
fun PromptListScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: PromptListViewModel = hiltViewModel()) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        ScreenHeader("Prompt editor", onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = SidePadding)) {
            Meta("Your saved version of a prompt is used instead of the built-in one. Small changes work best; run the test bench before and after.")
            if (rows.any { it.builtInChanged }) {
                Meta(
                    "An app update changed the built-in version of a prompt you edited. Open it to keep yours, or Reset to built-in to use the new one.",
                    Modifier.padding(top = 8.dp), color = Purpose.colors.accent,
                )
            }
            Row(Modifier.padding(vertical = 8.dp)) { TextAction("Export all prompts", vm::exportAll) }
            Hairline()
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(r.name) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, style = Purpose.type.itemText, color = Purpose.colors.text, modifier = Modifier.weight(1f))
                    Meta(
                        when {
                            r.builtInChanged -> "Edited · built-in updated"
                            r.edited -> "Edited"
                            else -> "Built-in"
                        },
                        color = if (r.edited) Purpose.colors.accent else Purpose.colors.textMuted,
                    )
                }
                Hairline()
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

data class PromptEditorState(
    val name: String = "",
    val text: String? = null,
    val edited: Boolean = false,
    val saved: Boolean = false,
    /** The built-in version changed in an update since he saved his. */
    val builtInChanged: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
)

@HiltViewModel
class PromptEditorViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val prompts: PromptRepository,
) : ViewModel() {
    val name: String = saved.get<String>("name").orEmpty()
    private val _state = MutableStateFlow(PromptEditorState(name))
    val state: StateFlow<PromptEditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = PromptEditorState(name, prompts.load(name), prompts.isEdited(name), builtInChanged = name in prompts.builtInChangedSinceEdit())
        }
    }

    fun save(text: String) {
        if (_state.value.saving) return
        _state.value = _state.value.copy(saving = true, error = null, saved = false)
        viewModelScope.launch {
            try {
                prompts.save(name, text)
                _state.value = PromptEditorState(name, text, edited = true, saved = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(saving = false, error = e.message ?: "Couldn't save this prompt.")
            }
        }
    }

    fun reset() {
        if (_state.value.saving) return
        _state.value = _state.value.copy(saving = true, error = null, saved = false)
        viewModelScope.launch {
            try {
                prompts.reset(name)
                _state.value = PromptEditorState(name, prompts.builtIn(name), edited = false, saved = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(saving = false, error = e.message ?: "Couldn't reset this prompt.")
            }
        }
    }
}

@Composable
fun PromptEditorScreen(onBack: () -> Unit, onOpenTestBench: () -> Unit, vm: PromptEditorViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val loaded = state.text ?: run {
        Column(Modifier.fillMaxSize().background(Purpose.colors.background)) { ScreenHeader(vm.name, onBack = onBack, small = true) }
        return
    }
    // Keyed by what's stored, so Reset refills the editor.
    var text by rememberSaveable(loaded) { mutableStateOf(loaded) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val dirty = text != loaded
    val leave = { if (dirty) confirmLeave = true else onBack() }
    BackHandler(onBack = leave)
    Column(Modifier.fillMaxSize().background(Purpose.colors.background).imePadding()) {
        ScreenHeader(vm.name, onBack = leave, small = true) {
            TextAction(if (state.saving) "Saving…" else "Save", { vm.save(text) }, enabled = dirty && !state.saving)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = SidePadding), verticalAlignment = Alignment.CenterVertically) {
            Meta(if (state.edited) "Edited" else "Built-in", Modifier.weight(1f), color = if (state.edited) Purpose.colors.accent else Purpose.colors.textMuted)
            if (state.edited) TextAction("Reset to built-in", { confirmReset = true }, color = Purpose.colors.textMuted, enabled = !state.saving)
        }
        if (state.builtInChanged) {
            Meta(
                "The built-in version was updated since you saved yours. Keep yours, or Reset to built-in to use the new one.",
                Modifier.padding(horizontal = SidePadding), color = Purpose.colors.accent,
            )
        }
        state.error?.let { Meta(it, Modifier.padding(horizontal = SidePadding), color = Purpose.colors.accent) }
        if (state.saved && !dirty) {
            Row(Modifier.fillMaxWidth().padding(horizontal = SidePadding), verticalAlignment = Alignment.CenterVertically) {
                Meta("Saved. Run the test bench to see how it changes replies.", Modifier.weight(1f))
                TextAction("Test bench", onOpenTestBench)
            }
        }
        Hairline()
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            readOnly = state.saving,
            textStyle = Purpose.type.userBody.copy(color = Purpose.colors.text),
            cursorBrush = SolidColor(Purpose.colors.accent),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = SidePadding, vertical = 12.dp).navigationBarsPadding(),
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            text = "Reset ${vm.name} to built-in? Your edits will be gone; the built-in version is used from the next conversation.",
            confirm = "Reset",
            onConfirm = { confirmReset = false; vm.reset() },
            onDismiss = { confirmReset = false },
        )
    }
    if (confirmLeave) {
        ConfirmDialog(
            text = "Leave without saving? Your changes to ${vm.name} haven't been saved.",
            confirm = "Leave",
            onConfirm = { confirmLeave = false; onBack() },
            onDismiss = { confirmLeave = false },
        )
    }
}
