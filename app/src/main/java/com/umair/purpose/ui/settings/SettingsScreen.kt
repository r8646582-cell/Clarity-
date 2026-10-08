package com.umair.purpose.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.umair.purpose.ai.AiConfig
import com.umair.purpose.backup.BackupCodec
import com.umair.purpose.chat.ToughLove
import com.umair.purpose.cost.CostEstimate
import com.umair.purpose.cost.Prices
import com.umair.purpose.data.db.UsageTotals
import com.umair.purpose.data.repo.ThemeChoice
import com.umair.purpose.data.repo.VoiceLanguage
import com.umair.purpose.ui.common.ConfirmDialog
import com.umair.purpose.ui.common.Hairline
import com.umair.purpose.ui.common.LabeledField
import com.umair.purpose.ui.common.Meta
import com.umair.purpose.ui.common.PrimaryButton
import com.umair.purpose.ui.common.PurposeTextField
import com.umair.purpose.ui.common.ScreenHeader
import com.umair.purpose.ui.common.Segmented
import com.umair.purpose.ui.common.SidePadding
import com.umair.purpose.ui.common.SwitchRow
import com.umair.purpose.ui.common.TextAction
import com.umair.purpose.promise.ReminderScheduler
import com.umair.purpose.system.Reliability
import com.umair.purpose.ui.theme.Purpose
import com.umair.purpose.ui.theme.PurposeIcons
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDeveloper: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    val snackbar = com.umair.purpose.ui.common.LocalSnackbar.current
    LifecycleResumeEffect(Unit) {
        vm.refreshLastBackup()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        ScreenHeader("Settings", onBack = onBack)
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = SidePadding),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            ApiKeySection(state.hasApiKey, onSave = { vm.saveApiKey(it); snackbar("Key saved") }, onRemove = vm::removeApiKey)
            val s = state.settings
            if (s != null) {
                Section("How direct should I be?") {
                    Segmented(ToughLove.entries.map { it to it.wire.replaceFirstChar { c -> c.uppercase() } }, s.toughLove, vm::setToughLove)
                }
                Section("Theme") {
                    Segmented(ThemeChoice.entries.map { it to it.label }, s.theme, vm::setTheme)
                }
                Section("Daily life") {
                    SwitchRow("Daily pulse", s.pulseEnabled, vm::setPulse, "A ten-second check-in on Talk, once a day.")
                    SwitchRow("Read replies aloud", s.readAloud, vm::setReadAloud, "Or tap the speaker under any reply.")
                    VoiceRows(vm, s.voiceLanguage)
                    val vibration by vm.vibration.collectAsStateWithLifecycle()
                    SwitchRow("Vibration", vibration, vm::setVibration, "A light tap when you send, keep a promise, and a few other moments.")
                }
                Section("Privacy") {
                    SwitchRow("Lock Purpose", s.lockEnabled, vm::setLock, "Fingerprint, face or PIN when you open it, and after 5 minutes away.")
                    SwitchRow(
                        "Hide in recent apps", s.hideInRecents, vm::setHideInRecents,
                        "Also blocks screenshots. Turn off temporarily if you need to take one.",
                    )
                }
            }
            ScreenTimeSection()
            KeepReliableSection()
            BackupSection(state.backup, onExport = vm::export, onImport = vm::restore, onDismissStatus = vm::clearBackupStatus) { passphrase ->
                AutoBackupRows(s?.autoBackup == true, passphrase, vm)
                ExportLifeRow(onPick = vm::exportLife)
            }
            if (s != null) OnceAYear(s, vm)
            state.cost?.let { cost -> BudgetLines(cost.usd, s?.monthlyBudget ?: 0.0) }

            Column {
                Hairline()
                Row(
                    Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Advanced", style = Purpose.type.label, color = Purpose.colors.text, modifier = Modifier.weight(1f))
                    Icon(PurposeIcons.Chevron, contentDescription = null, tint = Purpose.colors.textMuted, modifier = Modifier.size(18.dp))
                }
                if (advanced && s != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(32.dp)) {
                        // Keyed so the form refills if the stored config changes underneath it.
                        key(s.ai) { ProviderSection(s.ai) { vm.saveAi(it); snackbar("Saved") } }
                        Section("Which model answers") {
                            Meta("Heavy or long messages, tools and first messages go to the deep model with thinking on. Everything else goes to the fast one.")
                            SwitchRow("Always use the deep model", s.alwaysDeep, vm::setAlwaysDeep, "Slower and costs more, but every reply thinks first.")
                        }
                        key(s.chatPrices, s.deepPrices) { PricesSection(s.chatPrices, s.deepPrices) { c, d -> vm.savePrices(c, d); snackbar("Saved") } }
                        Section("Voice language") {
                            Segmented(VoiceLanguage.entries.map { it to it.label }, s.voiceLanguage, vm::setVoiceLanguage)
                            Meta("Voice typing uses your phone's speech service.")
                        }
                        key(s.monthlyBudget) { BudgetField(s.monthlyBudget) { vm.setBudget(it); snackbar("Saved") } }
                        UsageSection(state.usage, state.cost)
                        state.breakdown?.let { CostBreakdownSection(it) }
                        key(s.offPeak) { OffPeakSection(s.offPeak) { on, w, f -> vm.saveOffPeak(on, w, f); snackbar("Saved") } }
                        key(s.backupProvider) {
                            BackupProviderSection(s.backupProvider, state.hasBackupKey,
                                onSave = { k, u, key, c, d -> vm.saveBackupProvider(k, u, key, c, d); snackbar("Saved") },
                                onRemove = vm::removeBackupProvider)
                        }
                        EraseEverything(vm)
                        VersionAndDeveloper(onOpenDeveloper)
                    }
                }
                Hairline()
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Voice picker, speed and pitch, and a way to better voices. */
@Composable
private fun VoiceRows(vm: SettingsViewModel, language: VoiceLanguage) {
    val voices by vm.voices.collectAsStateWithLifecycle()
    val chosen by vm.voiceName.collectAsStateWithLifecycle()
    val speed by vm.voiceSpeed.collectAsStateWithLifecycle()
    val pitch by vm.voicePitch.collectAsStateWithLifecycle()
    var showAll by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(language) { vm.loadVoices(language) }
    Column(Modifier.padding(start = 0.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Voice", style = Purpose.type.label, color = Purpose.colors.text)
        Meta("Phone voices can sound robotic. Try a different voice.")
        val list = voices
        when {
            list == null -> Meta("Looking for voices…")
            list.isEmpty() -> Meta("No voices for this language yet. Tap Get better voices to download one.")
            else -> {
                val shown = if (showAll) list else list.take(5)
                VoiceChoice("Phone default", "The default voice for this language", chosen == null || list.none { it.name == chosen }) { vm.pickVoice(null) }
                shown.forEach { v ->
                    VoiceChoice(v.label, if (v.online) "Online voice: the text goes to the voice service to be spoken" else "On this phone", v.name == chosen) { vm.pickVoice(v.name) }
                }
                if (list.size > shown.size) TextAction("Show all (${list.size})", { showAll = true }, color = Purpose.colors.textMuted)
            }
        }
        Text("Speed", style = Purpose.type.label, color = Purpose.colors.text, modifier = Modifier.padding(top = 8.dp))
        PurposeSlider(speed, vm::setVoiceSpeed)
        Text("Pitch", style = Purpose.type.label, color = Purpose.colors.text)
        PurposeSlider(pitch, vm::setVoicePitch)
        Row {
            TextAction("Try it", { vm.tryVoice(language) })
            TextAction("Get better voices", vm::openVoiceSettings, color = Purpose.colors.textMuted)
        }
    }
}

@Composable
private fun VoiceChoice(title: String, note: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(18.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (selected) Purpose.colors.accent else Purpose.colors.surface),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Purpose.type.userBody, color = Purpose.colors.text)
            Meta(note)
        }
    }
}

@Composable
private fun PurposeSlider(value: Float, onChange: (Float) -> Unit) {
    androidx.compose.material3.Slider(
        value = value,
        onValueChange = { onChange((Math.round(it * 10) / 10f).coerceIn(0.5f, 2f)) },
        valueRange = 0.5f..2f,
        steps = 14,
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = Purpose.colors.accent,
            activeTrackColor = Purpose.colors.accent,
            inactiveTrackColor = Purpose.colors.surface,
            activeTickColor = Purpose.colors.accent.copy(alpha = 0f),
            inactiveTickColor = Purpose.colors.surface.copy(alpha = 0f),
        ),
    )
}

/** The version line, then Developer: a normal row (CLAUDE.md "Developer menu (visible)"). */
@Composable
private fun VersionAndDeveloper(onOpenDeveloper: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    Column {
        Meta("Purpose $version")
        Spacer(Modifier.height(12.dp))
        Hairline()
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenDeveloper).padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Developer", style = Purpose.type.label, color = Purpose.colors.text)
                Meta("Health check, test bench, prompt editor, error log")
            }
            Icon(PurposeIcons.Chevron, contentDescription = null, tint = Purpose.colors.textMuted, modifier = Modifier.size(18.dp))
        }
    }
}

fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = Purpose.type.heading, color = Purpose.colors.text)
        content()
    }
}

@Composable
private fun ApiKeySection(hasKey: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var changing by rememberSaveable { mutableStateOf(false) }
    Section("Your key") {
        if (hasKey && !changing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PurposeIcons.Check, contentDescription = null, tint = Purpose.colors.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Connected", style = Purpose.type.label, color = Purpose.colors.text, modifier = Modifier.weight(1f))
                TextAction("Change", { changing = true })
            }
            Meta("Your DeepSeek key is saved, encrypted on this phone.")
        } else {
            Meta("Paste your DeepSeek API key. It stays on this phone, encrypted.")
            PurposeTextField(
                value = key,
                onValueChange = { key = it },
                placeholder = "sk-…",
                singleLine = true,
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("Save key", { onSave(key); key = ""; changing = false }, enabled = key.isNotBlank())
                Spacer(Modifier.weight(1f))
                if (hasKey) {
                    TextAction("Cancel", { changing = false; key = "" }, color = Purpose.colors.textMuted)
                    TextAction("Remove key", { onRemove(); changing = false }, color = Purpose.colors.danger)
                }
            }
        }
    }
}

@Composable
private fun ProviderSection(config: AiConfig, onSave: (AiConfig) -> Unit) {
    var provider by rememberSaveable { mutableStateOf(config.provider) }
    var baseUrl by rememberSaveable { mutableStateOf(config.baseUrl) }
    var chatModel by rememberSaveable { mutableStateOf(config.chatModel) }
    var deepModel by rememberSaveable { mutableStateOf(config.deepModel) }
    var temperature by rememberSaveable { mutableStateOf(config.chatTemperature.toString()) }
    var thinkingToggle by rememberSaveable { mutableStateOf(config.supportsThinkingToggle) }

    val parsedTemp = temperature.toDoubleOrNull()?.takeIf { it in 0.0..2.0 }
    val edited = config.copy(
        provider = provider.trim(),
        baseUrl = baseUrl.trim(),
        chatModel = chatModel.trim(),
        deepModel = deepModel.trim(),
        chatTemperature = parsedTemp ?: config.chatTemperature,
        supportsThinkingToggle = thinkingToggle,
    )
    val valid = parsedTemp != null && edited.baseUrl.startsWith("https://") &&
        edited.chatModel.isNotEmpty() && edited.deepModel.isNotEmpty()

    Section("Provider") {
        Field("Provider", provider) { provider = it }
        Field("Base URL (https)", baseUrl, KeyboardType.Uri) { baseUrl = it }
        Field("Chat model (fast)", chatModel) { chatModel = it }
        Field("Deep model (heavy chats, letters, reflection)", deepModel) { deepModel = it }
        Field("Chat temperature (0 to 2)", temperature, KeyboardType.Decimal) { temperature = it }
        SwitchRow("Send the thinking switch", thinkingToggle, { thinkingToggle = it }, "DeepSeek's on/off field: thinking on for the deep model, off for the fast one.")
        PrimaryButton("Save", { onSave(edited) }, enabled = valid && edited != config)
    }
}

@Composable
private fun Field(label: String, value: String, type: KeyboardType = KeyboardType.Ascii, onChange: (String) -> Unit) {
    LabeledField(label) {
        PurposeTextField(value, onChange, singleLine = true, keyboardType = type, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun UsageSection(usage: UsageTotals?, cost: CostEstimate?) {
    val n = NumberFormat.getIntegerInstance()
    Section("Usage this month") {
        if (usage == null || usage.requests == 0) {
            Meta("Nothing yet.")
        } else {
            UsageLine("Requests", n.format(usage.requests))
            UsageLine("Input tokens, cached", n.format(usage.cacheHitTokens))
            UsageLine("Input tokens, not cached", n.format(usage.cacheMissTokens))
            UsageLine("Output tokens", n.format(usage.completionTokens))
            if (cost != null) {
                UsageLine("Estimated cost", "$" + String.format(Locale.US, "%.2f", cost.usd) + if (cost.complete) "" else " +")
                if (!cost.complete) Meta("Some usage has no price set, so the real cost is a little higher.")
            }
        }
    }
}

@Composable
private fun PricesSection(chat: Prices, deep: Prices, onSave: (Prices, Prices) -> Unit) {
    fun fmt(d: Double) = if (d == 0.0) "" else d.toString()
    val values = remember {
        mutableStateListOf(
            fmt(chat.cacheHit), fmt(chat.cacheMiss), fmt(chat.output),
            fmt(deep.cacheHit), fmt(deep.cacheMiss), fmt(deep.output),
        )
    }
    val parsed = values.map { v -> if (v.isBlank()) 0.0 else v.trim().toDoubleOrNull()?.takeIf { it >= 0 } }
    val valid = parsed.all { it != null }
    val labels = listOf("cached in", "uncached in", "output")

    Section("Prices (USD per 1M tokens)") {
        Meta("Prefilled with DeepSeek's prices. Used only for the cost estimate.")
        listOf("Chat model" to 0, "Letters and reflection model" to 3).forEach { (title, offset) ->
            Text(title, style = Purpose.type.label, color = Purpose.colors.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, label ->
                    Column(Modifier.weight(1f)) {
                        Meta(label)
                        PurposeTextField(values[offset + i], { values[offset + i] = it }, singleLine = true, keyboardType = KeyboardType.Decimal)
                    }
                }
            }
        }
        PrimaryButton(
            "Save prices",
            {
                val p = parsed.map { it ?: 0.0 }
                onSave(Prices(p[0], p[1], p[2]), Prices(p[3], p[4], p[5]))
            },
            enabled = valid,
        )
    }
}

@Composable
private fun BackupSection(
    status: BackupStatus,
    onExport: (Uri, String) -> Unit,
    onImport: (Uri, String) -> Unit,
    onDismissStatus: () -> Unit,
    extra: @Composable (passphrase: String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf(false) }
    val ok = passphrase.length >= BackupCodec.MIN_PASSPHRASE && status != BackupStatus.Working

    // Backing out of the system picker writes nothing, and the screen used to say nothing either, so he could not
    // tell whether a backup had happened at all.
    var exportCancelled by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        exportCancelled = uri == null
        if (uri != null) onExport(uri, passphrase)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri, passphrase)
    }

    Section("Backup") {
        Meta(
            "Saves everything to a file, locked with a passphrase you choose. Without the passphrase the file can't be read, " +
                "and it can't be recovered. Your API key is not included."
        )
        PurposeTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            placeholder = "Backup passphrase (8+ characters)",
            singleLine = true,
            keyboardType = KeyboardType.Password,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton("Export", { exportCancelled = false; exportLauncher.launch("purpose-backup-${LocalDate.now()}.pbak") }, enabled = ok)
            Spacer(Modifier.width(8.dp))
            TextAction("Restore", { confirmRestore = true }, enabled = ok)
        }
        when (status) {
            BackupStatus.Idle -> Unit
            BackupStatus.Working -> Meta("Working…")
            is BackupStatus.Done -> Meta(status.message, color = Purpose.colors.accent)
            is BackupStatus.Failed -> Meta(status.message, color = Purpose.colors.danger)
        }
        if (exportCancelled) Meta("Nothing was saved.", color = Purpose.colors.danger)
        extra(passphrase)
    }

    if (confirmRestore) {
        ConfirmDialog(
            text = "Restore from backup? This replaces everything on this phone with what's in the file.",
            confirm = "Choose file",
            danger = false,
            onConfirm = {
                confirmRestore = false
                onDismissStatus()
                importLauncher.launch(arrayOf("*/*"))
            },
            onDismiss = { confirmRestore = false },
        )
    }
}

/**
 * CLAUDE.md "Keep Purpose reliable on Xiaomi/Redmi": battery optimization off, Autostart on, and the app
 * locked in Recents. The battery status is read again every time Settings comes back into view.
 */
@Composable
private fun KeepReliableSection() {
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(Reliability.batteryUnrestricted(context)) }
    var exactAlarms by remember { mutableStateOf(ReminderScheduler.canScheduleExactAlarms(context)) }
    LifecycleResumeEffect(Unit) {
        unrestricted = Reliability.batteryUnrestricted(context)
        exactAlarms = ReminderScheduler.canScheduleExactAlarms(context)
        onPauseOrDispose { }
    }
    val xiaomi = remember { Reliability.isXiaomi() }
    Section("Keep Purpose reliable") {
        Meta(
            "Some phones, Xiaomi and Redmi especially, stop apps in the background. That can cut off a reply, " +
                "or stop reminders and letters."
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (unrestricted) "Battery: unrestricted" else "Battery: restricted",
                style = Purpose.type.label,
                color = if (unrestricted) Purpose.colors.accent else Purpose.colors.text,
                modifier = Modifier.weight(1f),
            )
            if (!unrestricted) TextAction("Allow", { Reliability.requestUnrestricted(context) })
        }
        // Without exact alarms, a reminder can arrive up to ten minutes late. Only shown when Android says no.
        if (!exactAlarms) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Reminders: may run late",
                    style = Purpose.type.label,
                    color = Purpose.colors.text,
                    modifier = Modifier.weight(1f),
                )
                TextAction("Allow", { runCatching { context.startActivity(ReminderScheduler.createExactAlarmSettingIntent(context)) } })
            }
        }
        if (xiaomi) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Autostart", style = Purpose.type.label, color = Purpose.colors.text)
                    Meta("The phone doesn't let apps check this. Make sure it's on.")
                }
                TextAction("Open", { Reliability.openAutostart(context) })
            }
        }
        Meta(
            "1. Battery saver: set Purpose to No restrictions.\n" +
                (if (xiaomi) "2. Autostart: turn it on for Purpose.\n3. " else "2. ") +
                "In Recents, long-press Purpose and lock it, so clearing Recents doesn't close it."
        )
    }
}

/** "Back up automatically every week" to a folder he picks, with the passphrase typed above. */
@Composable
private fun AutoBackupRows(on: Boolean, passphrase: String, vm: SettingsViewModel) {
    val last by vm.lastBackupAt.collectAsStateWithLifecycle()
    var needPassphrase by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.enableAutoBackup(uri, passphrase)
    }
    SwitchRow(
        "Back up automatically every week",
        on,
        { want ->
            when {
                !want -> vm.disableAutoBackup()
                passphrase.length < BackupCodec.MIN_PASSPHRASE -> needPassphrase = true
                else -> { needPassphrase = false; pickFolder.launch(null) }
            }
        },
        "To a folder you pick (a phone folder or Google Drive), locked with the passphrase above. Keeps the last 4.",
    )
    if (needPassphrase && !on) Meta("Type a backup passphrase above first (8+ characters).", color = Purpose.colors.danger)
    if (on) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Meta(if (last > 0) "Last backup: ${ago(last)}" else "No automatic backup yet.", Modifier.weight(1f))
            TextAction("Back up now", vm::backupNow, color = Purpose.colors.textMuted)
        }
    }
}

private fun ago(at: Long): String {
    val days = ((System.currentTimeMillis() - at) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        else -> "$days days ago"
    }
}

/** "This month: about $0.42 of $5.00", and the cost guard's quiet warnings. */
@Composable
private fun BudgetLines(usd: Double, budget: Double) {
    fun money(d: Double) = "$" + String.format(Locale.US, "%.2f", d)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Meta("This month: about ${money(usd)}" + if (budget > 0) " of ${money(budget)}" else "")
        when {
            budget <= 0 -> Unit
            usd >= budget -> Meta("Budget reached: chats use the fast model until the month ends.", color = Purpose.colors.accent)
            usd >= budget * 0.8 -> Meta("You've used most of this month's budget.", color = Purpose.colors.accent)
        }
    }
}

@Composable
private fun BudgetField(current: Double, onSave: (Double) -> Unit) {
    var text by rememberSaveable { mutableStateOf(String.format(Locale.US, "%.2f", current)) }
    val parsed = text.trim().toDoubleOrNull()?.takeIf { it >= 0 }
    Section("Monthly budget (USD)") {
        Meta("At 80% you'll see a note here. At 100%, chats use the fast model until the month ends. Letters and reflection still run. 0 means no limit.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            PurposeTextField(text, { text = it }, singleLine = true, keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextAction("Save", { parsed?.let(onSave) }, enabled = parsed != null && parsed != current)
        }
    }
}

/** Settings > Advanced: wipes all his data, keeps settings and the key. He types ERASE to confirm. */
@Composable
private fun EraseEverything(vm: SettingsViewModel) {
    var asking by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    val erased by vm.erased.collectAsStateWithLifecycle()
    Section("Erase everything") {
        Meta("Deletes every conversation, everything Purpose has learned, promises, journeys and letters. Settings and your key stay.")
        if (erased) Meta("Everything is erased.", color = Purpose.colors.accent)
        TextAction("Erase everything", { asking = true; typed = "" }, color = Purpose.colors.danger)
    }
    if (asking) {
        AlertDialog(
            onDismissRequest = { asking = false },
            containerColor = Purpose.colors.surface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(16.dp),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This can't be undone. Type ERASE to confirm.", style = Purpose.type.itemText, color = Purpose.colors.text)
                    PurposeTextField(typed, { typed = it }, singleLine = true, placeholder = "ERASE", modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextAction("Erase", { asking = false; vm.eraseEverything() }, color = Purpose.colors.danger, enabled = typed.trim() == "ERASE")
            },
            dismissButton = { TextAction("Cancel", { asking = false }, color = Purpose.colors.textMuted) },
        )
    }
}

@Composable
private fun UsageLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = Purpose.type.userBody, color = Purpose.colors.text, modifier = Modifier.weight(1f))
        Text(value, style = Purpose.type.userBody, color = Purpose.colors.textMuted)
    }
}

/**
 * UPDATE-15 "Export my life": everything, unencrypted and readable, so his history outlives this app, this phone
 * and this AI provider. A folder he picks gets one Markdown file and the same data as JSON.
 */
@Composable
private fun ExportLifeRow(onPick: (Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) onPick(uri) }
    Spacer(Modifier.height(8.dp))
    Text("Export my life", style = Purpose.type.label, color = Purpose.colors.text)
    Meta("Everything, as a readable document and a data file, in a folder you choose. Not encrypted: anyone who opens them can read them, so keep them private.")
    TextAction("Choose a folder and export", { launcher.launch(null) })
}

/** UPDATE-16 (MAINTENANCE.md "Once a year"): the checks that keep Purpose for life. Shown on the Health check too. */
@Composable
private fun OnceAYear(s: com.umair.purpose.data.repo.AppSettings, vm: SettingsViewModel) {
    fun since(at: Long?) = at?.let { "Last done " + java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() } ?: "Not done yet"
    Section("Once a year") {
        Meta("A backup you've never restored is a hope, not a backup. And without your signing keystore and its passwords, you can't update the app.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("I tested a restore", style = Purpose.type.label, color = Purpose.colors.text)
                Meta(since(s.lastRestoreTestAt))
            }
            TextAction("Done today", vm::markRestoreTested)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("My keystore and its 4 passwords are saved safely", style = Purpose.type.label, color = Purpose.colors.text)
                Meta(since(s.keystoreConfirmedAt))
            }
            TextAction("Confirm", vm::confirmKeystore)
        }
        var expiry by rememberSaveable(s.providerKeyExpiry) { mutableStateOf(s.providerKeyExpiry.orEmpty()) }
        Field("API key expires on (if you know, yyyy-mm-dd)", expiry) { expiry = it }
        TextAction("Save", { vm.setKeyExpiry(expiry) }, enabled = expiry.isBlank() || runCatching { LocalDate.parse(expiry.trim()) }.isSuccess)
    }
}

/** UPDATE-17: where the money goes this month, and how much of the input the provider served from its cache. */
@Composable
private fun CostBreakdownSection(b: com.umair.purpose.cost.Breakdown) {
    fun money(d: Double) = "$" + String.format(Locale.US, if (d < 0.1) "%.3f" else "%.2f", d)
    Section("Where the money goes") {
        if (b.features.isEmpty()) {
            Meta("Nothing yet this month.")
            return@Section
        }
        b.features.forEach { f -> UsageLine("${f.feature.label} (${f.requests})", money(f.usd)) }
        b.cacheHitRate?.let { UsageLine("Input served from cache", "${(it * 100).toInt()}%") }
        b.deepShare?.let { UsageLine("Chat messages on the deep model", "${(it * 100).toInt()}%") }
        if ((b.cacheHitRate ?: 1.0) < 0.6) Meta("A low cache rate means something at the start of each request keeps changing. Worth a look in the Health check.")
    }
}

/** UPDATE-17: run background work in the provider's cheaper hours. Peak windows are in UTC. */
@Composable
private fun OffPeakSection(c: com.umair.purpose.cost.OffPeakConfig, onSave: (Boolean, String, Double) -> Unit) {
    var on by remember { mutableStateOf(c.enabled) }
    var windows by remember { mutableStateOf(c.peakWindows) }
    var factor by remember { mutableStateOf(c.factor.toString()) }
    Section("Off-peak hours") {
        Meta("DeepSeek charges half price outside its busy hours. Reflection backlog, letters, chapters, gardening and the growth tree wait for the cheaper hours (at most a few hours). Chat is never delayed.")
        SwitchRow("Use off-peak hours", on, { on = it })
        Field("Busy hours, UTC (e.g. 01:00-04:00,06:00-10:00)", windows) { windows = it }
        Field("Off-peak price as a share (0.5 = half)", factor, KeyboardType.Decimal) { factor = it }
        val f = factor.trim().toDoubleOrNull()
        PrimaryButton("Save", { onSave(on, windows, f ?: 0.5) }, enabled = f != null && f > 0 && f <= 1 && com.umair.purpose.cost.OffPeak.parse(windows).isNotEmpty())
    }
}

/**
 * UPDATE-16 "Backup provider": if the main AI provider fails 3 times in a row, Purpose switches to this one and
 * says so quietly on Talk, then switches back when the main one works again.
 */
@Composable
private fun BackupProviderSection(
    current: com.umair.purpose.data.repo.BackupProvider?,
    hasKey: Boolean,
    onSave: (kind: String, baseUrl: String, key: String, chat: String, deep: String) -> Unit,
    onRemove: () -> Unit,
) {
    var kind by remember { mutableStateOf(current?.kind ?: com.umair.purpose.data.repo.BackupProvider.OPENAI) }
    var url by remember { mutableStateOf(current?.baseUrl.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var chat by remember { mutableStateOf(current?.chatModel.orEmpty()) }
    var deep by remember { mutableStateOf(current?.deepModel.orEmpty()) }
    Section("Backup provider") {
        Meta("Used only when your main provider fails 3 times in a row (an outage, a shut-down, an expired key, no credit). Any OpenAI-compatible API, or Anthropic. Its key stays on this phone, encrypted.")
        Segmented(
            listOf(com.umair.purpose.data.repo.BackupProvider.OPENAI to "OpenAI-compatible", com.umair.purpose.data.repo.BackupProvider.ANTHROPIC to "Anthropic"),
            kind, { kind = it },
        )
        Field("Base URL", url, KeyboardType.Uri) { url = it }
        if (kind == com.umair.purpose.data.repo.BackupProvider.ANTHROPIC && url.isBlank()) Meta("Leave empty for https://api.anthropic.com")
        PurposeTextField(
            value = key, onValueChange = { key = it },
            placeholder = if (hasKey) "Key saved (type to replace)" else "API key",
            singleLine = true, keyboardType = KeyboardType.Password, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Field("Fast model name", chat) { chat = it }
        Field("Deep model name (optional)", deep) { deep = it }
        val urlOk = url.isNotBlank() || kind == com.umair.purpose.data.repo.BackupProvider.ANTHROPIC
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                "Save",
                { onSave(kind, url.ifBlank { if (kind == com.umair.purpose.data.repo.BackupProvider.ANTHROPIC) "https://api.anthropic.com" else url }, key, chat, deep); key = "" },
                enabled = urlOk && chat.isNotBlank() && (hasKey || key.isNotBlank()),
            )
            Spacer(Modifier.width(8.dp))
            if (current != null) TextAction("Remove", onRemove, color = Purpose.colors.textMuted)
        }
        if (current == null) Meta("No backup provider set: if the main one fails, replies just fail as before.")
    }
}
