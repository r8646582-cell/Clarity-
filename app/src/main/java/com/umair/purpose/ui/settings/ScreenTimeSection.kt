package com.umair.purpose.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.umair.purpose.data.repo.ScreenTimeRepository
import com.umair.purpose.data.repo.UiPrefs
import com.umair.purpose.screen.ScreenTimeSummary
import com.umair.purpose.ui.common.ConfirmDialog
import com.umair.purpose.ui.common.Meta
import com.umair.purpose.ui.common.SwitchRow
import com.umair.purpose.ui.common.TextAction
import com.umair.purpose.ui.theme.Purpose
import com.umair.purpose.work.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What the Settings card needs to draw. */
data class ScreenTimeState(
    val enabled: Boolean = false,
    val hasAccess: Boolean = false,
    val rows: Int = 0,
    val summary: List<String> = emptyList(),
)

/**
 * Phase 4: opt-in phone screen time. Off by default. Turning it on sends him to Android's own "usage access"
 * screen; turning it off stops collection and deletes everything collected.
 */
@HiltViewModel
class ScreenTimeViewModel @Inject constructor(
    private val repo: ScreenTimeRepository,
    private val prefs: UiPrefs,
    private val work: WorkScheduler,
) : ViewModel() {
    private val _state = MutableStateFlow(ScreenTimeState(enabled = prefs.screenTimeEnabled))
    val state: StateFlow<ScreenTimeState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { repo.all() }
            val access = repo.hasAccess()
            _state.value = ScreenTimeState(
                enabled = prefs.screenTimeEnabled, hasAccess = access, rows = rows.size,
                summary = ScreenTimeSummary.days(rows),
            )
            // He granted access in system Settings and came back: start collecting without another tap.
            if (prefs.screenTimeEnabled && access && rows.isEmpty()) work.screenTimeNow()
        }
    }

    /** On: remember the choice and schedule the daily copy. Collection starts once usage access is granted. */
    fun enable() {
        prefs.screenTimeEnabled = true
        work.setScreenTime(true)
        if (repo.hasAccess()) work.screenTimeNow()
        refresh()
    }

    /** Off: stop, and delete everything collected. */
    fun disable() {
        prefs.screenTimeEnabled = false
        work.setScreenTime(false)
        deleteAll()
    }

    fun deleteAll() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteAll() }
            refresh()
        }
    }

    fun accessIntent() = repo.accessSettingsIntent()

    suspend fun csv(): String = withContext(Dispatchers.IO) { repo.csv() }
}

@Composable
fun ScreenTimeSection(vm: ScreenTimeViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmOff by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf(false) }
    var pendingCsv by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        val text = pendingCsv
        pendingCsv = null
        if (uri != null && text != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Phone screen time", style = Purpose.type.heading, color = Purpose.colors.text)
        SwitchRow(
            "Compare my words with my phone",
            s.enabled,
            { want -> if (want) vm.enable() else confirmOff = true },
            "Off by default. When on, Purpose reads how many minutes you used your phone each hour, grouped by kind of app " +
                "(social, video, games...). It never stores which app or what was on screen. The records stay on this phone; " +
                "only a few summary lines (weekly averages, phone time around your promises) go to your AI provider with the " +
                "rest of what Purpose knows, to check what you said against what happened. Turning it off deletes the records.",
        )
        if (s.enabled && !s.hasAccess) {
            Meta("One more step: Android needs you to allow \"usage access\" for Purpose in its own settings.")
            TextAction("Open usage access settings", { context.startActivity(vm.accessIntent()) })
        }
        if (s.enabled && s.hasAccess && s.rows == 0) Meta("Allowed. The first numbers appear after the next daily copy, usually within a day.")
        if (s.rows > 0) {
            Meta("${s.rows} hourly records stored on this phone, encrypted with the rest of your data.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction("View", { viewing = true })
                TextAction("Export CSV", {
                    scope.launch { pendingCsv = vm.csv(); saveCsv.launch("purpose-screen-time.csv") }
                })
                TextAction("Delete all", { confirmDelete = true }, color = Purpose.colors.danger)
            }
            Meta("Exported files are not encrypted. The data is also in Export my life and in backups.")
        }
    }

    if (confirmOff) ConfirmDialog(
        "Turn off and delete all screen-time data collected so far? This can't be undone.", "Turn off and delete",
        onConfirm = { confirmOff = false; vm.disable() }, onDismiss = { confirmOff = false },
    )
    if (confirmDelete) ConfirmDialog(
        "Delete all screen-time data? Collection continues if it is still switched on.", "Delete",
        onConfirm = { confirmDelete = false; vm.deleteAll() }, onDismiss = { confirmDelete = false },
    )
    if (viewing) AlertDialog(
        onDismissRequest = { viewing = false },
        containerColor = Purpose.colors.surface,
        shape = RoundedCornerShape(16.dp),
        title = { Text("Last 14 days, minutes", style = Purpose.type.heading, color = Purpose.colors.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.summary.forEach { Text(it, style = Purpose.type.itemText, color = Purpose.colors.text) }
            }
        },
        confirmButton = { TextAction("Close", { viewing = false }) },
    )
}
