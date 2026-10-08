package com.umair.purpose.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.umair.purpose.ai.AiJob
import com.umair.purpose.ai.JobModelChoice
import com.umair.purpose.ai.ModelSource
import com.umair.purpose.cost.FeatureUsage
import com.umair.purpose.cost.JobCosts
import com.umair.purpose.cost.Prices
import com.umair.purpose.data.repo.AppSettings
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.data.repo.UsageRepository
import com.umair.purpose.security.SecretStore
import com.umair.purpose.ui.common.LabeledField
import com.umair.purpose.ui.common.Meta
import com.umair.purpose.ui.common.PurposeTextField
import com.umair.purpose.ui.common.TextAction
import com.umair.purpose.ui.theme.Purpose
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/** One row of the card: the job, its current choice, and what it cost this month. */
data class JobRow(
    val job: AiJob,
    val choice: JobModelChoice,
    val modelInUse: String,
    val actualUsd: Double,
    val complete: Boolean,
    val requests: Int,
    /** This month's tokens re-priced for the chosen model, when its prices are known. */
    val projectedUsd: Double?,
)

data class JobModelsState(
    val rows: List<JobRow> = emptyList(),
    val hasBackup: Boolean = false,
    val backupAnthropic: Boolean = false,
    val hasBackupKey: Boolean = false,
    val loaded: Boolean = false,
)

/** Phase 5: which model each slow job uses, and what those jobs cost. Chat keeps its own fast/deep routing. */
@HiltViewModel
class JobModelsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    usage: UsageRepository,
    private val secrets: SecretStore,
) : ViewModel() {
    val state: StateFlow<JobModelsState> =
        combine(settings.observe(), usage.observeThisMonthBreakdown()) { s, u -> build(s, u) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JobModelsState())

    private fun build(s: AppSettings, usage: List<FeatureUsage>): JobModelsState {
        val factor = if (s.offPeak.enabled) s.offPeak.factor else 1.0
        return JobModelsState(
            rows = AiJob.entries.map { job ->
                val choice = s.jobModels[job] ?: JobModelChoice()
                val resolved = s.jobModel(job)
                val line = JobCosts.actual(job, usage, s.pricing)
                val prices = resolved.prices ?: when (choice.source) {
                    ModelSource.MAIN_FAST -> s.chatPrices
                    ModelSource.DEFAULT, ModelSource.MAIN_DEEP -> s.deepPrices
                    else -> null
                }
                JobRow(job, choice, resolved.model, line.actualUsd, line.complete, line.requests, JobCosts.projected(job, usage, prices, factor))
            },
            hasBackup = s.backupProvider != null,
            backupAnthropic = s.backupProvider?.anthropic == true,
            hasBackupKey = !secrets.backupApiKey().isNullOrBlank(),
            loaded = true,
        )
    }

    fun set(job: AiJob, choice: JobModelChoice) {
        viewModelScope.launch { settings.setJobModel(job, choice) }
    }

    /** Points every slow job at the strongest model he has, only when he asks. */
    fun useStrongest(backupAnthropic: Boolean) {
        val source = if (backupAnthropic) ModelSource.BACKUP_DEEP else ModelSource.MAIN_DEEP
        viewModelScope.launch {
            AiJob.entries.forEach { job ->
                val current = state.value.rows.firstOrNull { it.job == job }?.choice ?: JobModelChoice()
                settings.setJobModel(job, current.copy(source = source))
            }
        }
    }

    fun reset() {
        viewModelScope.launch { AiJob.entries.forEach { settings.setJobModel(it, JobModelChoice()) } }
    }
}

private fun money(d: Double) = "$" + String.format(Locale.US, "%.2f", d)

@Composable
fun JobModelsSection(vm: JobModelsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    if (!s.loaded) return
    var editing by remember { mutableStateOf<AiJob?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Models for slow jobs", style = Purpose.type.heading, color = Purpose.colors.text)
        Meta(
            "Reflection, letters, life chapters, memory gardening and the snapshot are written rarely and shape what Purpose " +
                "remembers for years. Each can use its own model. Chat keeps its fast and deep routing. Nothing here changes " +
                "until you pick something, and \"Same as before\" is the default.",
        )
        s.rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.job.label, style = Purpose.type.label, color = Purpose.colors.text)
                    Meta("${row.choice.source.label}: ${row.modelInUse}")
                    Meta(costLine(row))
                }
                TextAction("Change", { editing = row.job })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction("Use the strongest available", { vm.useStrongest(s.backupAnthropic) })
            TextAction("Reset", vm::reset, color = Purpose.colors.textMuted)
        }
        Meta(
            if (s.backupAnthropic) "Strongest available: the backup provider's deep model (Anthropic). It costs more per token; check the cost line first."
            else "Strongest available: the main provider's deep model. Add an Anthropic backup provider below to reach a stronger one.",
        )
        Meta("Estimates come from this month's logged usage. Prices you enter are yours; the app does not know other providers' prices.")
    }

    editing?.let { job ->
        val row = s.rows.first { it.job == job }
        EditJobModel(row, s.hasBackup, s.hasBackupKey, onDismiss = { editing = null }) { vm.set(job, it); editing = null }
    }
}

private fun costLine(r: JobRow): String {
    val actual = if (r.requests == 0) "No use yet this month."
    else "This month: ${money(r.actualUsd)} over ${r.requests} requests" + if (r.complete) "." else " (some usage has no price entered)."
    val projected = r.projectedUsd?.takeIf { r.requests > 0 }?.let { " The same usage on this choice: about ${money(it)}." }.orEmpty()
    return actual + projected
}

@Composable
private fun EditJobModel(row: JobRow, hasBackup: Boolean, hasBackupKey: Boolean, onDismiss: () -> Unit, onSave: (JobModelChoice) -> Unit) {
    var source by remember { mutableStateOf(row.choice.source) }
    var custom by remember { mutableStateOf(row.choice.customModel) }
    var hit by remember { mutableStateOf(row.choice.prices.cacheHit.takeIf { it > 0 }?.toString().orEmpty()) }
    var miss by remember { mutableStateOf(row.choice.prices.cacheMiss.takeIf { it > 0 }?.toString().orEmpty()) }
    var out by remember { mutableStateOf(row.choice.prices.output.takeIf { it > 0 }?.toString().orEmpty()) }
    val needsPrices = source == ModelSource.BACKUP_DEEP || source == ModelSource.CUSTOM
    fun num(t: String) = t.trim().toDoubleOrNull()?.takeIf { it >= 0 } ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Purpose.colors.surface,
        shape = RoundedCornerShape(16.dp),
        title = { Text(row.job.label, style = Purpose.type.heading, color = Purpose.colors.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelSource.entries.forEach { opt ->
                    val enabled = opt != ModelSource.BACKUP_DEEP || hasBackup
                    TextAction(
                        (if (opt == source) "● " else "○ ") + opt.label, { source = opt }, enabled = enabled,
                        color = if (opt == source) Purpose.colors.accent else Purpose.colors.text,
                    )
                }
                if (source == ModelSource.BACKUP_DEEP && !hasBackupKey)
                    Meta("The backup provider has no API key yet, so this job would fail. Add the key first.", color = Purpose.colors.danger)
                if (source == ModelSource.CUSTOM) LabeledField("Model name") {
                    PurposeTextField(custom, { custom = it }, singleLine = true)
                }
                if (needsPrices) {
                    Meta("Optional: USD per 1M tokens, from your provider's price page. Without them the cost estimate says it is incomplete.")
                    LabeledField("Input, cached") { PurposeTextField(hit, { hit = it }, singleLine = true, keyboardType = KeyboardType.Decimal) }
                    LabeledField("Input, not cached") { PurposeTextField(miss, { miss = it }, singleLine = true, keyboardType = KeyboardType.Decimal) }
                    LabeledField("Output") { PurposeTextField(out, { out = it }, singleLine = true, keyboardType = KeyboardType.Decimal) }
                }
            }
        },
        confirmButton = {
            TextAction("Save", {
                onSave(JobModelChoice(source, custom.trim(), if (needsPrices) Prices(num(hit), num(miss), num(out)) else Prices(0.0, 0.0, 0.0)))
            })
        },
        dismissButton = { TextAction("Cancel", onDismiss, color = Purpose.colors.textMuted) },
    )
}
