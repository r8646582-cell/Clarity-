package com.umair.purpose.ui.talk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.activity.compose.BackHandler
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.State
import com.umair.purpose.chat.LiveText
import com.umair.purpose.ui.common.rememberClickHaptic
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.umair.purpose.chat.PracticeName
import com.umair.purpose.chat.Tier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.umair.purpose.data.db.Message
import com.umair.purpose.data.db.Promise
import com.umair.purpose.memory.OnboardingTopic
import com.umair.purpose.data.repo.VoiceLanguage
import com.umair.purpose.ui.common.Block
import com.umair.purpose.ui.common.Hairline
import com.umair.purpose.ui.common.IconAction
import com.umair.purpose.ui.common.MenuAction
import com.umair.purpose.ui.common.Meta
import com.umair.purpose.ui.common.OverflowMenu
import com.umair.purpose.ui.common.PrimaryButton
import com.umair.purpose.ui.common.PurposeSheet
import com.umair.purpose.ui.common.PurposeTextField
import com.umair.purpose.ui.common.Ridgeline
import com.umair.purpose.ui.common.ScaleRow
import com.umair.purpose.ui.common.ScreenHeader
import com.umair.purpose.ui.common.SidePadding
import com.umair.purpose.ui.common.TextAction
import com.umair.purpose.ui.path.JourneyListSheet
import androidx.paging.compose.collectAsLazyPagingItems
import com.umair.purpose.ui.theme.Purpose
import com.umair.purpose.ui.theme.PurposeIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TalkScreen(
    onOpenSettings: () -> Unit,
    onOpenPromises: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onOpenTree: (Long) -> Unit = {},
    vm: TalkViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    // The reply's text as it streams, from memory. Only the streaming message reads it (inside its own effect),
    // so a chunk arriving never recomposes the screen.
    val live = vm.live.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var input by rememberSaveable { mutableStateOf("") }
    var showTools by remember { mutableStateOf(false) }
    var askWho by remember { mutableStateOf(false) }
    var pickJourney by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Message?>(null) }
    val click = rememberClickHaptic()
    val snackbar = com.umair.purpose.ui.common.LocalSnackbar.current
    // True while older-than-latest content sits behind the input area: show a hairline on its top edge.
    var behindInput by remember { mutableStateOf(false) }
    // Bumped on every send so the conversation jumps to his new message.
    var sendTick by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }

    LaunchedEffect(state.autoOnboarding) { if (state.autoOnboarding) onOpenOnboarding() }

    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        vm.eventFlow.collect { e ->
            when (e) {
                TalkEvent.AskNotificationPermission ->
                    if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                TalkEvent.PickJourney -> pickJourney = true
                is TalkEvent.Grow -> onOpenTree(e.leafId)
            }
        }
    }

    // Voice typing: the words land in the input box for him to edit; never sent by itself.
    var listening by remember { mutableStateOf(false) }
    val voice = remember {
        VoiceTyping(context, onText = { heard -> input = if (input.isBlank()) heard else input.trimEnd() + " " + heard }, onState = { listening = it })
    }
    DisposableEffect(Unit) { onDispose { voice.stop() } }
    val voiceLanguage = state.settings?.voiceLanguage ?: VoiceLanguage.DEFAULT
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.start(voiceLanguage)
    }
    val onMic: () -> Unit = {
        if (listening) {
            voice.stop(); listening = false
        } else if (!voice.available) {
            // Common on phones without Google's speech service: say so instead of a mic that does nothing.
            android.widget.Toast.makeText(context, "Voice typing isn't available on this phone.", android.widget.Toast.LENGTH_SHORT).show()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voice.start(voiceLanguage)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val conversations = vm.conversations.collectAsLazyPagingItems()
    val drawerQuery by vm.drawerQuery.collectAsStateWithLifecycle()
    fun closeDrawer() = drawerScope.launch { drawerState.close() }
    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }
    // Reading a past conversation: back returns to the current one (it used to leave the app).
    BackHandler(enabled = !drawerState.isOpen && state.viewingPast) { vm.endConversation() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = state.hasApiKey,
        scrimColor = Color.Black.copy(alpha = 0.35f),
        drawerContent = {
            Box(Modifier.fillMaxWidth(0.85f).fillMaxHeight()) {
                ConversationsDrawer(
                    conversations = conversations,
                    query = drawerQuery,
                    onQuery = { vm.drawerQuery.value = it },
                    openId = state.session?.id,
                    onNew = { vm.newConversation(); closeDrawer() },
                    onOpen = { id -> vm.openConversation(id); closeDrawer() },
                    onRename = { id, t -> vm.renameConversation(id, t); snackbar("Renamed") },
                    onForget = vm::forgetConversation,
                )
            }
        },
    ) {
    Column(Modifier.fillMaxSize().background(Purpose.colors.background).imePadding()) {
        ScreenHeader(
            "Purpose",
            small = true,
            leading = { IconAction(PurposeIcons.Menu, "Conversations", { drawerScope.launch { drawerState.open() } }, tint = Purpose.colors.text) },
        ) {
            OverflowMenu(
                listOf(
                    // Always there: the way out of any mode or conversation.
                    MenuAction("New conversation") { vm.newConversation() },
                    MenuAction("Off the record", enabled = !state.offTheRecord) { vm.startOffTheRecord() },
                    MenuAction("Settings", onClick = onOpenSettings),
                )
            )
        }
        if (state.offTheRecord) {
            Row(Modifier.fillMaxWidth().padding(start = SidePadding, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Meta("Off the record: nothing here will be remembered.", Modifier.weight(1f))
                TextAction("End", vm::endConversation, color = Purpose.colors.textMuted)
            }
            Hairline()
        }
        // UPDATE-16: quiet lines, never alarms.
        if (state.usingBackupAi && state.hasApiKey) {
            Meta("Using your backup AI for now.", Modifier.fillMaxWidth().padding(horizontal = SidePadding, vertical = 4.dp))
        } else if (state.offline && state.hasApiKey) {
            Meta("You're offline. You can read everything, and what you write will send when you're back.", Modifier.fillMaxWidth().padding(horizontal = SidePadding, vertical = 4.dp))
        }
        state.modeHeader?.let { header ->
            Row(Modifier.fillMaxWidth().padding(start = SidePadding, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Meta(header, Modifier.weight(1f))
                TextAction("End", vm::endConversation, color = Purpose.colors.textMuted)
            }
            Hairline()
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !state.loaded -> Unit
                !state.hasApiKey -> NoKey(onOpenSettings)
                state.isNew -> NewSession(
                    state = state,
                    onDismissCard = vm::dismissCard,
                    onCardAction = { card ->
                        when (card) {
                            TalkCard.OnboardingIntro -> onOpenOnboarding()
                            is TalkCard.OnboardingTopicCard -> vm.startOnboardingTopic(card.topic)
                            is TalkCard.JourneyStep -> vm.startJourneyStep(card.journey)
                            TalkCard.PulseCheck -> Unit
                            TalkCard.KeepReliable -> { vm.dismissCard(card); onOpenSettings() }
                            is TalkCard.Milestone -> { click(); vm.acceptMilestone(card.milestone) }
                        }
                    },
                    onMilestoneLater = { vm.snoozeMilestone(it) },
                    onMilestoneNever = { vm.declineMilestone(it) },
                    onSavePulse = { m, e, w -> click(); vm.savePulse(m, e, w) },
                    onOpenPromise = onOpenPromises,
                    onPickUp = vm::openConversation,
                )
                // Keyed by conversation, so opening another one starts at its latest message.
                else -> key(state.session?.id) {
                    Conversation(
                        state, live, sendTick, onOpenPromises, onSpeak = vm::speak, onDetails = { details = it },
                        onBehindInput = { behindInput = it }, onTryAgain = vm::retryInterrupted,
                        onUndoPromise = { ps -> ps.forEach(vm::undoSavedPromise); snackbar(if (ps.size > 1) "Promises removed" else "Promise removed") },
                        onContinueStep = { next -> click(); vm.continueOnboarding(next) },
                        onStepLater = vm::dismissStepDone,
                        onLoadOlder = vm::loadOlder,
                    )
                }
            }
        }

        // An interrupted reply carries its own "Try again"; otherwise the error sits above the input.
        state.error?.takeIf { state.messages.lastOrNull()?.interrupted != true }?.let { err ->
            Row(Modifier.fillMaxWidth().padding(horizontal = SidePadding), verticalAlignment = Alignment.CenterVertically) {
                Meta(err, Modifier.weight(1f))
                if (state.canRetry) TextAction("Retry", vm::retry)
            }
        } ?: run {
            if (state.canRetry && !state.sending) {
                Row(Modifier.fillMaxWidth().padding(horizontal = SidePadding), verticalAlignment = Alignment.CenterVertically) {
                    Meta("No reply yet.", Modifier.weight(1f))
                    TextAction("Retry", vm::retry)
                }
            }
        }

        if (state.hasApiKey) {
            // The snackbar sits above this area, never on top of the box he types in.
            val lift = com.umair.purpose.ui.common.LocalSnackbarLift.current
            val density = LocalDensity.current
            DisposableEffect(Unit) { onDispose { lift.value = 0.dp } }
            Column(Modifier.fillMaxWidth().onGloballyPositioned { lift.value = with(density) { it.size.height.toDp() } }) {
            if (behindInput && !state.isNew) Hairline()
            ListenChip(state.listenOnly, onToggle = { click(); vm.setListenOnly(!state.listenOnly) })
            InputRow(
                value = input,
                onValueChange = { input = it },
                onSend = {
                    // Refused (a reply is still on its way): his words stay in the box.
                    if (!vm.send(input)) return@InputRow
                    click()
                    input = ""
                    // The keyboard closes as soon as he sends; tapping the field opens it again.
                    focusManager.clearFocus()
                    keyboard?.hide()
                    sendTick++
                },
                canSend = input.isNotBlank() && !state.sending && !state.replyElsewhere,
                onPlus = { click(); showTools = true },
                onMic = onMic,
                listening = listening,
            )
            }
        }
    }

    }

    if (showTools) {
        ToolsSheet(
            onDismiss = { showTools = false },
            onPractice = { showTools = false; askWho = true },
            onDecision = { showTools = false; vm.startDecision() },
            onUntangle = { showTools = false; vm.startUntangle() },
            onJourney = { showTools = false; pickJourney = true },
        )
    }
    details?.let { m -> MessageDetailsSheet(m, onDismiss = { details = null }) }
    if (askWho) AskWhoSheet(onDismiss = { askWho = false }) { who -> askWho = false; vm.startPractice(who) }
    if (pickJourney) JourneyListSheet(onDismiss = { pickJourney = false }) { name -> pickJourney = false; vm.startJourney(name) }
}

@Composable
private fun NoKey(onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = SidePadding), verticalArrangement = Arrangement.Center) {
        Ridgeline()
        Spacer(Modifier.height(24.dp))
        Text("Add your DeepSeek key to begin.", style = Purpose.type.openingLine, color = Purpose.colors.text)
        Spacer(Modifier.height(8.dp))
        Meta("It stays on this phone, encrypted.")
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Open settings", onOpenSettings)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewSession(
    state: TalkUiState,
    onDismissCard: (TalkCard) -> Unit,
    onCardAction: (TalkCard) -> Unit,
    onSavePulse: (Int, Int, String) -> Unit,
    onMilestoneLater: (com.umair.purpose.data.db.Milestone) -> Unit = {},
    onMilestoneNever: (com.umair.purpose.data.db.Milestone) -> Unit = {},
    onOpenPromise: () -> Unit = {},
    onPickUp: (Long) -> Unit = {},
) {
    // The one memorable moment: the opening line fades in once per session.
    val alpha = remember(state.session?.id, state.opening) { Animatable(if (state.opening.isEmpty()) 1f else 0f) }
    val reduceMotion = Purpose.reduceMotion
    LaunchedEffect(state.session?.id, state.opening) {
        if (reduceMotion) alpha.snapTo(1f) else alpha.animateTo(1f, tween(600))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = SidePadding)) {
        Spacer(Modifier.height(48.dp))
        Column(Modifier.alpha(alpha.value)) {
            if (state.plainHome) {
                // UPDATE-19 "Talk: home": a time-band greeting, never an AI-written line that could be stale.
                Text(state.greeting, style = Purpose.type.openingLine, color = Purpose.colors.text)
                Spacer(Modifier.height(8.dp))
                Text(state.opening, style = Purpose.type.itemText, color = Purpose.colors.textMuted)
            } else {
                if (state.modeHeader == null) Meta(state.greeting)
                Spacer(Modifier.height(8.dp))
                Text(state.opening, style = Purpose.type.openingLine, color = Purpose.colors.text)
            }
        }
        Spacer(Modifier.height(24.dp))
        AnimatedVisibility(visible = state.isNew, exit = fadeOut(tween(if (reduceMotion) 0 else 300))) { Ridgeline() }
        Spacer(Modifier.height(32.dp))
        if (state.comingUp.isNotEmpty()) {
            HomeLabel("Coming up")
            state.comingUp.forEach { HomeRow(it.title, it.meta, onOpenPromise) }
            Spacer(Modifier.height(16.dp))
        }
        state.pickUp?.let { p ->
            HomeLabel("Pick up where you left off")
            HomeRow(p.title, p.meta) { onPickUp(p.sessionId) }
            Spacer(Modifier.height(16.dp))
        }
        state.card?.let { card -> key(card) {
            val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = {
                if (it != SwipeToDismissBoxValue.Settled) onDismissCard(card)
                true
            })
            SwipeToDismissBox(state = dismiss, backgroundContent = {}) {
                TalkCardView(card, onAction = { onCardAction(card) }, onSavePulse = onSavePulse, onLater = onMilestoneLater, onNever = onMilestoneNever)
            }
        } }
        Spacer(Modifier.height(24.dp))
    }
}

/** "Coming up", "Pick up where you left off" (DESIGN.md "Talk: home"). */
@Composable
private fun HomeLabel(text: String) {
    Text(text, style = Purpose.type.label, color = Purpose.colors.textMuted)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun HomeRow(title: String, meta: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Text(title, style = Purpose.type.itemText, color = Purpose.colors.text, maxLines = 2)
        Meta(meta)
    }
}

@Composable
private fun TalkCardView(
    card: TalkCard,
    onAction: () -> Unit,
    onSavePulse: (Int, Int, String) -> Unit,
    onLater: (com.umair.purpose.data.db.Milestone) -> Unit = {},
    onNever: (com.umair.purpose.data.db.Milestone) -> Unit = {},
) {
    Block {
        when (card) {
            // UPDATE-18 (CLAUDE.md "How he sees a proposal").
            is TalkCard.Milestone -> {
                val m = card.milestone
                Meta("Something I've noticed")
                Text(m.title, style = Purpose.type.itemText, color = Purpose.colors.text, modifier = Modifier.padding(top = 4.dp))
                com.umair.purpose.growth.MilestoneRules.decodeEvidence(m.evidenceJson).forEach { Meta(it, Modifier.padding(top = 2.dp)) }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextAction("Add to your tree", onAction)
                    Spacer(Modifier.width(8.dp))
                    TextAction("Not yet", { onLater(m) }, color = Purpose.colors.textMuted)
                }
                TextAction("This isn't right", { onNever(m) }, color = Purpose.colors.textMuted)
            }
            TalkCard.OnboardingIntro -> CardBody("Getting to know you", "A few short steps over your first week.", "Begin", onAction)
            is TalkCard.OnboardingTopicCard -> CardBody("Getting to know you, ${card.number} of 5", card.topic.title, "Start", onAction)
            is TalkCard.JourneyStep ->
                CardBody("${card.journey.name}, day ${card.journey.currentDay}: ${card.theme}", "Today's step", "Start", onAction)
            TalkCard.PulseCheck -> PulseCard(onSavePulse)
            TalkCard.KeepReliable -> CardBody(
                "Keep Purpose reliable",
                "Your phone may stop replies, reminders and letters in the background. Two settings fix it.",
                "Show me",
                onAction,
            )
        }
    }
}

/** UPDATE-12: under the reply that closed a "getting to know you" step. */
@Composable
private fun StepDoneCardView(step: StepDoneUi, onContinue: (OnboardingTopic) -> Unit, onLater: () -> Unit) {
    Block {
        Text("${step.done.title}: done", style = Purpose.type.itemText, color = Purpose.colors.text)
        Meta(step.next?.let { "Next: ${it.title}" } ?: "That's all five. Your snapshot is on its way.")
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            step.next?.let { next ->
                TextAction("Continue now", { onContinue(next) })
                Spacer(Modifier.width(16.dp))
            }
            TextAction(if (step.next != null) "Later" else "OK", onLater, color = Purpose.colors.textMuted)
        }
    }
}

@Composable
private fun CardBody(title: String, subtitle: String, action: String, onAction: () -> Unit) {
    Text(title, style = Purpose.type.itemText, color = Purpose.colors.text)
    Meta(subtitle)
    TextAction(action, onAction, Modifier.padding(top = 4.dp))
}

@Composable
private fun PulseCard(onSave: (Int, Int, String) -> Unit) {
    var mood by remember { mutableIntStateOf(0) }
    var energy by remember { mutableIntStateOf(0) }
    var word by remember { mutableStateOf("") }
    Text("How are you today?", style = Purpose.type.itemText, color = Purpose.colors.text)
    Spacer(Modifier.height(12.dp))
    Meta("Mood")
    ScaleRow(mood.takeIf { it > 0 }, { mood = it }, "low", "great")
    Spacer(Modifier.height(8.dp))
    Meta("Energy")
    ScaleRow(energy.takeIf { it > 0 }, { energy = it }, "low", "great")
    Spacer(Modifier.height(12.dp))
    PurposeTextField(word, { word = it.take(30) }, placeholder = "One word, if you like", singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
        TextAction("Save", { onSave(mood, energy, word) }, enabled = mood > 0 && energy > 0)
    }
}

private sealed interface Row2 {
    val key: String
    data class Day(val label: String, override val key: String) : Row2
    data class Msg(val message: Message, val showAs: Boolean) : Row2 { override val key = "m${message.id}" }
    data class StepDone(val step: StepDoneUi, override val key: String) : Row2
    data object Typing : Row2 { override val key = "typing" }
}

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")

private fun dayLabel(ms: Long): String {
    val d = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DAY_FORMAT)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Conversation(
    state: TalkUiState,
    live: State<LiveText?>,
    sendTick: Int,
    onOpenPromises: () -> Unit,
    onSpeak: (String) -> Unit,
    onDetails: (Message) -> Unit,
    onBehindInput: (Boolean) -> Unit,
    onTryAgain: (Long) -> Unit,
    onUndoPromise: (List<Promise>) -> Unit,
    onContinueStep: (OnboardingTopic) -> Unit,
    onStepLater: () -> Unit,
    onLoadOlder: () -> Unit = {},
) {
    val rows = buildList {
        var lastDay: String? = null
        var lastWasInCharacter = false
        state.messages.forEach { m ->
            val day = dayLabel(m.createdAt)
            if (day != lastDay) add(Row2.Day(day, "d${m.id}"))
            lastDay = day
            val inChar = m.id in state.inCharacter
            add(Row2.Msg(m, showAs = inChar && !lastWasInCharacter))
            if (m.role == Message.ROLE_ASSISTANT) lastWasInCharacter = inChar
            state.stepDone?.takeIf { it.messageId == m.id && !m.streaming }?.let { add(Row2.StepDone(it, "s${m.id}")) }
        }
        // Before the reply's row exists, the dots stand alone; after that, the row itself shows them.
        if (state.sending && state.messages.none { it.streaming }) add(Row2.Typing)
    }
    val latest = state.messages.lastOrNull()
    val reduceMotion = Purpose.reduceMotion
    // A conversation opens at its latest message.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = rows.lastIndex.coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // DESIGN.md "Keyboard and scrolling (like Claude)": when he sends, HIS message moves near the top and the
    // reply streams in below it. Nothing follows the stream: the reply grows down to the bottom of the screen
    // and then carries on below it, so he reads from the start. A blank runway under the newest rows makes room
    // for his message to reach the top even when little comes after it.
    var anchorKey by remember { mutableStateOf<String?>(null) }
    var waitingForSent by remember { mutableStateOf(false) }
    val topGap = with(density) { 8.dp.roundToPx() }
    val runwayPx by remember {
        derivedStateOf {
            val key = anchorKey ?: return@derivedStateOf 0
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo.filter { it.key != RUNWAY_KEY }
            val anchor = visible.firstOrNull { it.key == key } ?: return@derivedStateOf 0
            val lastReal = info.totalItemsCount - 2 // the runway is the last item
            // Something after the anchor is out of sight below: the content is already taller than the screen.
            if (visible.lastOrNull()?.index != lastReal) return@derivedStateOf 0
            val after = visible.last().let { it.offset + it.size } - anchor.offset
            (info.viewportEndOffset - info.afterContentPadding - info.viewportStartOffset - topGap - after - info.mainAxisItemSpacing)
                .coerceAtLeast(0)
        }
    }
    // Only a send made while THIS conversation is on screen counts: sends from an earlier one must not make a
    // freshly opened conversation scroll as if he had just written.
    val tickAtOpen = remember { sendTick }
    LaunchedEffect(sendTick) { if (sendTick > tickAtOpen) waitingForSent = true }
    val newestUserKey = rows.lastOrNull { it is Row2.Msg && it.message.role == Message.ROLE_USER }?.key
    LaunchedEffect(newestUserKey) {
        if (!waitingForSent || newestUserKey == null) return@LaunchedEffect
        waitingForSent = false
        anchorKey = newestUserKey
        // One frame for the runway to size itself, then put his message at the top.
        withFrameNanos { }
        val index = rows.indexOfFirst { it.key == newestUserKey }
        if (index >= 0) {
            if (reduceMotion) listState.scrollToItem(index, -topGap) else listState.animateScrollToItem(index, -topGap)
        }
    }
    // The keyboard opening shrinks the list: if he was at the latest message, stay there. (It closing on send
    // needs nothing: the list just grows, and his message stays where the send put it.)
    val imeVisible = WindowInsets.isImeVisible
    var wasAtEnd by remember { mutableStateOf(true) }
    LaunchedEffect(listState) { snapshotFlow { !listState.canScrollForward }.collect { wasAtEnd = it } }
    LaunchedEffect(imeVisible) { if (imeVisible && wasAtEnd && rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex + 1) }

    // UPDATE-15: at the top of what's loaded, the next older page comes in above (the list keeps his place).
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.layoutInfo.totalItemsCount > 1 }
            .collect { atTop -> if (atTop) onLoadOlder() }
    }

    // Content below the visible area: the ↓ button, and the hairline over the input bar.
    val moreBelow by remember { derivedStateOf { listState.canScrollForward } }
    LaunchedEffect(moreBelow) { onBehindInput(moreBelow) }
    DisposableEffect(Unit) { onDispose { onBehindInput(false) } }

    // Rows already there when the conversation opened don't animate; new ones fade in and rise 6dp (180ms).
    // Coach replies don't: they were already on screen as they streamed.
    val shownKeys = remember { rows.map { it.key }.toMutableSet() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(horizontal = SidePadding, vertical = 16.dp),
        ) {
            items(rows, key = { it.key }) { row ->
                val animate = !reduceMotion && row.key !in shownKeys &&
                    !(row is Row2.Msg && row.message.role == Message.ROLE_ASSISTANT)
                SideEffect { shownKeys += row.key }
                Box(if (animate) Modifier.enterOnce() else Modifier) {
                    when (row) {
                        is Row2.Day -> Meta(row.label, Modifier.fillMaxWidth(), align = TextAlign.Center)
                        is Row2.Msg -> {
                            val m = row.message
                            when {
                                m.role == Message.ROLE_USER -> Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                                    UserBubble(m.content)
                                    // UPDATE-16: written offline; goes out on its own when the internet is back.
                                    if (m.queued) Meta("Will send when you're online", Modifier.padding(top = 4.dp))
                                }
                                else -> CoachRow(
                                    m = m,
                                    live = live,
                                    // Only the reply on its way needs to know about sending; others stay put.
                                    sending = state.sending && (m.streaming || m.id == latest?.id),
                                    deep = state.deep,
                                    inCharacter = m.id in state.inCharacter,
                                    asLabel = if (row.showAs) state.practiceWith?.let { "as $it" } else null,
                                    saved = state.savedPromises[m.id].orEmpty(),
                                    receipts = state.actionReceipts[m.id].orEmpty(),
                                    canTryAgain = m.id == latest?.id && !state.viewingPast,
                                    reduceMotion = reduceMotion,
                                    onOpenPromises = onOpenPromises,
                                    onSpeak = onSpeak,
                                    onDetails = onDetails,
                                    onTryAgain = onTryAgain,
                                    onUndoPromise = onUndoPromise,
                                )
                            }
                        }
                        is Row2.StepDone -> StepDoneCardView(row.step, onContinue = onContinueStep, onLater = onStepLater)
                        Row2.Typing -> TypingDots(thinking = state.deep)
                    }
                }
            }
            item(key = RUNWAY_KEY) { Spacer(Modifier.fillMaxWidth().height(with(density) { runwayPx.toDp() })) }
        }
        if (moreBelow) {
            // DESIGN.md: centered, 36dp, 12dp above the input area, so it never sits on his message bubbles.
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).size(36.dp).clip(CircleShape)
                    .background(Purpose.colors.surface.copy(alpha = 0.9f))
                    .border(1.dp, Purpose.colors.hairline, CircleShape)
                    .clickable {
                        scope.launch { listState.animateScrollToItem(rows.lastIndex + 1) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(PurposeIcons.ArrowDown, contentDescription = "Jump to the latest message", tint = Purpose.colors.text, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * A coach reply. On its way: dots until the first words, then the words flowing in smoothly (UPDATE-11). Dots only
 * while a reply really runs, so they can never outlive it. Speaker and Details appear once every word is shown.
 */
@Composable
private fun CoachRow(
    m: Message,
    live: State<LiveText?>,
    sending: Boolean,
    deep: Boolean,
    inCharacter: Boolean,
    asLabel: String?,
    saved: List<Promise>,
    receipts: List<com.umair.purpose.chat.ActionReceipt>,
    canTryAgain: Boolean,
    reduceMotion: Boolean,
    onOpenPromises: () -> Unit,
    onSpeak: (String) -> Unit,
    onDetails: (Message) -> Unit,
    onTryAgain: (Long) -> Unit,
    onUndoPromise: (List<Promise>) -> Unit,
) {
    val revealed = rememberRevealed(
        messageId = m.id,
        stored = m.content,
        streaming = m.streaming,
        live = live,
        startEmpty = m.streaming && sending,
        reduceMotion = reduceMotion,
    )
    if (m.streaming && sending && revealed.text.isBlank()) {
        TypingDots(thinking = deep)
        return
    }
    val settled = !m.streaming && revealed.text.length >= m.content.length
    Column {
        CoachMessage(
            revealed = revealed,
            inCharacter = inCharacter,
            asLabel = asLabel,
            saved = saved,
            onOpenPromises = onOpenPromises,
            onUndoPromise = onUndoPromise,
            onSpeak = if (settled) ({ onSpeak(m.content) }) else null,
            onLongPress = if (settled) ({ onDetails(m) }) else null,
        )
        receipts.filter { it.type in setOf("edit_promise", "reschedule_promise", "resolve_promise") || (it.type == "record_promise" && (!it.ok || it.promise?.sourceMessageId != m.id)) }.forEach { result ->
            Meta(com.umair.purpose.promise.PromiseConfirmation.action(result),
                Modifier.fillMaxWidth().clickable(onClick = onOpenPromises).padding(vertical = 6.dp),
            )
        }
        // A reply that isn't running any more but never finished counts as interrupted.
        if (m.interrupted || (m.streaming && !sending)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Meta("Reply interrupted.", Modifier.weight(1f))
                if (canTryAgain && !sending) {
                    TextAction("Try again", { onTryAgain(m.id) })
                }
            }
        }
    }
}

/** The blank space under the newest rows that lets his sent message sit near the top. */
private const val RUNWAY_KEY = "runway"

/** New rows: fade in plus a 6dp upward slide, 180ms, once. */
private fun Modifier.enterOnce(): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(180)) }
    val rise = with(LocalDensity.current) { 6.dp.toPx() }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * rise
    }
}

@Composable
private fun UserBubble(text: String) {
    // At most 80% of the width (DESIGN.md).
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        val max = maxWidth * 0.8f
        SelectionContainer {
            Text(
                text,
                style = Purpose.type.userBody,
                color = Purpose.colors.text,
                modifier = Modifier
                    .widthIn(max = max)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp))
                    .background(Purpose.colors.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoachMessage(
    revealed: Revealed,
    inCharacter: Boolean,
    asLabel: String?,
    saved: List<Promise>,
    onOpenPromises: () -> Unit,
    onUndoPromise: (List<Promise>) -> Unit,
    onSpeak: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    // Long-press opens Details (with Copy), so the text itself isn't a selection container.
    val press = if (onLongPress != null) Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress, indication = null, interactionSource = remember { MutableInteractionSource() }) else Modifier
    Column(Modifier.fillMaxWidth().then(press)) {
        asLabel?.let { Meta(it, Modifier.padding(bottom = 6.dp)) }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            if (inCharacter) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(Purpose.colors.accent))
                Spacer(Modifier.width(12.dp))
            }
            CoachParagraphs(revealed, Modifier.weight(1f))
        }
        if (saved.isNotEmpty() || onSpeak != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (saved.isNotEmpty()) {
                    Column(Modifier.weight(1f)) {
                        saved.forEach { promise ->
                            Meta(com.umair.purpose.promise.PromiseConfirmation.saved(promise),
                                Modifier.fillMaxWidth().clickable(onClick = onOpenPromises).padding(vertical = 6.dp))
                        }
                        // Undo deletes only promises created by this reply, never an edited existing promise.
                        TextAction("Undo", { onUndoPromise(saved) }, color = Purpose.colors.textMuted)
                    }
                }
                if (saved.isEmpty()) Spacer(Modifier.weight(1f))
                onSpeak?.let { IconAction(PurposeIcons.Speaker, "Read aloud", it) }
            }
        }
    }
}

/** Shown from send until the first words arrive. Deep replies think first, so they say so. */
@Composable
private fun TypingDots(thinking: Boolean) {
    val transition = rememberInfiniteTransition(label = "typing")
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(20_000)
        slow = true
    }
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                val a by transition.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(600, delayMillis = i * 200), RepeatMode.Reverse),
                    label = "dot$i",
                )
                Box(Modifier.size(6.dp).alpha(if (Purpose.reduceMotion) 0.6f else a).clip(CircleShape).background(Purpose.colors.textMuted))
            }
        }
        if (thinking) Meta(if (slow) "still thinking…" else "thinking…", Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ListenChip(on: Boolean, onToggle: () -> Unit) {
    Box(Modifier.padding(start = SidePadding, top = 8.dp, bottom = 8.dp)) {
        Text(
            if (on) "Just listening" else "Just listen",
            style = Purpose.type.label,
            color = if (on) Purpose.colors.text else Purpose.colors.textMuted,
            modifier = Modifier
                .clip(CircleShape)
                .background(if (on) Purpose.colors.surface else Color.Transparent)
                .border(1.dp, if (on) Color.Transparent else Purpose.colors.hairline, CircleShape)
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun InputRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    onPlus: () -> Unit,
    onMic: () -> Unit,
    listening: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        IconAction(PurposeIcons.Plus, "Tools", onPlus)
        PurposeTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = if (listening) "Listening…" else "Talk to me…",
            maxLines = 5,
            // When it grows to several lines, the mic stays on the bottom line with "+" and send.
            iconsAtBottom = true,
            modifier = Modifier.weight(1f),
            trailing = {
                IconAction(PurposeIcons.Mic, "Voice typing", onMic, tint = if (listening) Purpose.colors.accent else Purpose.colors.textMuted)
            },
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(if (canSend) Purpose.colors.accent else Purpose.colors.surface)
                .clickable(enabled = canSend, onClick = onSend),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                PurposeIcons.ArrowUp,
                contentDescription = "Send",
                tint = if (canSend) Purpose.colors.background else Purpose.colors.textMuted,
            )
        }
    }
}

/** Long-press a coach reply: how it was made (checks that routing works), and Copy. */
@Composable
private fun MessageDetailsSheet(m: Message, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    PurposeSheet(onDismiss) {
        Text("Details", style = Purpose.type.heading, color = Purpose.colors.text, modifier = Modifier.padding(bottom = 12.dp))
        if (m.model == null) {
            Meta("No details were recorded for this reply.")
        } else {
            DetailLine("Tier", Tier.fromWire(m.tier)?.label ?: "Unknown")
            DetailLine("Model", m.model)
            DetailLine("Thinking", if (m.thinking) "On" else "Off")
            DetailLine("Input tokens", m.inputTokens?.toString() ?: "Unknown")
            DetailLine("Output tokens", m.outputTokens?.toString() ?: "Unknown")
            DetailLine("Time to first words", m.firstTokenMs?.let(::seconds) ?: "Unknown")
            DetailLine("Total time", m.totalMs?.let(::seconds) ?: "Unknown")
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            TextAction("Copy text", { clipboard.setText(AnnotatedString(m.content)); onDismiss() })
        }
    }
}

private fun seconds(ms: Long) = String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = Purpose.type.userBody, color = Purpose.colors.text, modifier = Modifier.weight(1f))
        Text(value, style = Purpose.type.userBody, color = Purpose.colors.textMuted)
    }
}

@Composable
private fun ToolsSheet(
    onDismiss: () -> Unit,
    onPractice: () -> Unit,
    onDecision: () -> Unit,
    onUntangle: () -> Unit,
    onJourney: () -> Unit,
) {
    PurposeSheet(onDismiss) {
        listOf(
            Triple("Practice a conversation", "Rehearse something you're dreading.", onPractice),
            Triple("Think through a decision", "Get clear before you choose.", onDecision),
            Triple("Untangle a thought", "When a thought keeps hurting.", onUntangle),
            Triple("Start a journey", "A few days of focused work on one thing.", onJourney),
        ).forEach { (title, sub, action) ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = action).padding(vertical = 12.dp)) {
                Text(title, style = Purpose.type.itemText, color = Purpose.colors.text)
                Meta(sub)
            }
        }
    }
}

@Composable
private fun AskWhoSheet(onDismiss: () -> Unit, onStart: (String) -> Unit) {
    var who by remember { mutableStateOf("") }
    PurposeSheet(onDismiss) {
        Text("Who's it with?", style = Purpose.type.itemText, color = Purpose.colors.text)
        Spacer(Modifier.height(12.dp))
        PurposeTextField(
            who, { who = it.replace("\n", "").take(PracticeName.MAX) },
            placeholder = "e.g. Abbu", singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Meta("${who.length}/${PracticeName.MAX}", Modifier.padding(top = 4.dp))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
            PrimaryButton("Start", { onStart(who) }, enabled = who.isNotBlank())
        }
    }
}
