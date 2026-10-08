package com.umair.purpose.ui.know

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.itemsIndexed
import com.umair.purpose.ui.common.Hairline
import com.umair.purpose.ui.common.EmptyRidge
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.umair.purpose.data.db.AreaStatus
import com.umair.purpose.data.db.BehaviorEvent
import com.umair.purpose.data.db.Note
import com.umair.purpose.data.db.Person
import com.umair.purpose.data.db.ProfileEntry
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.Snapshot
import com.umair.purpose.data.db.Strength
import com.umair.purpose.ui.common.EditField
import com.umair.purpose.ui.common.EditSheet
import com.umair.purpose.ui.common.Heading
import com.umair.purpose.ui.common.Meta
import com.umair.purpose.ui.common.ScreenHeader
import com.umair.purpose.ui.common.Segmented
import com.umair.purpose.ui.common.SidePadding
import com.umair.purpose.ui.common.TextAction
import com.umair.purpose.ui.mirror.letterBlocks
import com.umair.purpose.ui.theme.Purpose
import com.umair.purpose.ui.theme.PurposeIcons
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateListOf
import com.umair.purpose.ui.common.PurposeTextField
import com.umair.purpose.ui.common.LocalSnackbar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private sealed interface Editing {
    data class Profile(val entry: ProfileEntry) : Editing
    data class PersonItem(val person: Person) : Editing
    data class NoteItem(val note: Note) : Editing
    data class Area(val area: AreaStatus) : Editing
    data class StrengthItem(val strength: Strength) : Editing
}

private val AREA_LABELS = mapOf(
    "eq" to "Emotional intelligence", "habits" to "Habits", "mindset" to "Mindset", "character" to "Character",
    "studies_career" to "Studies and career", "health" to "Health", "relationships" to "Relationships",
    "money" to "Money", "meaning" to "Meaning", "rest_joy" to "Rest and joy",
)

private val DATE = DateTimeFormatter.ofPattern("d MMM")

/** UPDATE-19: a small "confirmed" meta tag on entries he corrected or edited. */
private fun confirmed(meta: String?, yes: Boolean): String? = if (!yes) meta else listOfNotNull(meta, "confirmed").joinToString(", ")

private fun isFutureSelf(e: ProfileEntry) = e.key.lowercase().let { "future" in it || "vision" in it }

private fun keyLabel(key: String) = key.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun confidenceLabel(n: Note) = when (n.confidence) {
    "guess" -> "a guess"
    "likely" -> "likely"
    else -> "strongly supported"
}

private fun seen(n: Note, verb: String = "seen") = if (n.timesSeen == 1) "$verb once" else "$verb ${n.timesSeen} times"

/** One of What I know's sections: its rows, a count line, and how each row reads. */
private class KnowSectionSpec<T>(
    val title: String,
    /** "18" / "6 patterns" */
    val count: (Int) -> String,
    val rows: List<T>,
    val key: (T) -> String,
    val text: (T) -> String,
    val meta: (T) -> String?,
    val onEdit: (T) -> Unit,
    val onDelete: (T) -> Unit,
)

/**
 * DESIGN.md "What I know (calm, even after years)": title, intro and search; the snapshot and chapters rows; then
 * every section collapsed to one row with a count, opening in place to its top 5 (2 lines each; tap to read in
 * full, tap again to edit) and "Show all (N)". Search shows matches from every section, archived ones too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowScreen(
    onOpenSnapshot: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onOpenTalk: () -> Unit,
    onOpenChapters: () -> Unit = {},
    vm: KnowViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val archivedPages = vm.archivedPages.collectAsLazyPagingItems()
    val archivedMatches by vm.archivedMatches.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Editing?>(null) }
    val snackbar = LocalSnackbar.current
    // DESIGN.md "Small feedback": every swipe can be undone for 5 seconds.
    fun deleted(undo: () -> Unit) = snackbar("Deleted", "Undo", undo)

    var query by rememberSaveable { mutableStateOf("") }
    val open = remember { mutableStateListOf<String>() }
    val showingAll = remember { mutableStateListOf<String>() }
    val expandedItems = remember { mutableStateListOf<String>() }
    val q = query.trim()
    fun matches(vararg texts: String?) = q.isEmpty() || texts.any { it?.contains(q, ignoreCase = true) == true }
    // Tap once: the whole item. Tap again: edit it.
    fun tapItem(key: String, edit: () -> Unit) {
        if (key in expandedItems) edit() else expandedItems += key
    }

    val noteDelete: (Note) -> Unit = { vm.deleteNote(it); deleted { vm.restoreNote(it) } }
    fun notes(type: String) = state.notes.filter { it.type == type }
    val about = state.profile.filter { !isFutureSelf(it) }
    val future = state.profile.filter { isFutureSelf(it) }
    val sections: List<KnowSectionSpec<*>> = listOf(
        KnowSectionSpec("About you", { "$it" }, about, { "p${it.key}" }, { com.umair.purpose.memory.Corrections.display(it.value) }, { confirmed(keyLabel(it.key), com.umair.purpose.memory.Corrections.isProtected(it)) },
            { editing = Editing.Profile(it) }, { vm.deleteProfile(it); deleted { vm.restoreProfile(it) } }),
        KnowSectionSpec("Your strengths", { "$it" }, state.strengths, { "s${it.id}" }, { it.text }, { confirmed(null, it.editedByUser) },
            { editing = Editing.StrengthItem(it) }, { vm.deleteStrength(it); deleted { vm.restoreStrength(it) } }),
        KnowSectionSpec("How you work", { n -> if (n == 1) "1 pattern" else "$n patterns" }, notes("pattern"), { "n${it.id}" }, { it.text },
            { confirmed("${confidenceLabel(it)}, ${seen(it)}", it.editedByUser) }, { editing = Editing.NoteItem(it) }, noteDelete),
        KnowSectionSpec("What works for you", { "$it" }, notes("what_helps"), { "n${it.id}" }, { it.text }, { confirmed(seen(it, "worked"), it.editedByUser) },
            { editing = Editing.NoteItem(it) }, noteDelete),
        KnowSectionSpec("What doesn't", { "$it" }, notes("what_doesnt"), { "n${it.id}" }, { it.text }, { confirmed(seen(it), it.editedByUser) },
            { editing = Editing.NoteItem(it) }, noteDelete),
        KnowSectionSpec("Still on your mind", { "$it" }, notes("thread"), { "n${it.id}" }, { it.text }, { confirmed(null, it.editedByUser) },
            { editing = Editing.NoteItem(it) }, noteDelete),
        KnowSectionSpec("People", { "$it" }, state.people, { "pe${it.id}" }, { it.name + (it.relation?.let { r -> ", $r" } ?: "") }, { confirmed(it.notes, it.editedByUser) },
            { editing = Editing.PersonItem(it) }, { vm.deletePerson(it); deleted { vm.restorePerson(it) } }),
        KnowSectionSpec("Your life areas", { "$it" }, state.areas, { "a${it.area}" }, { (AREA_LABELS[it.area] ?: it.area) + ": " + it.status },
            { it.note }, { editing = Editing.Area(it) }, { vm.deleteArea(it); deleted { vm.restoreArea(it) } }),
        KnowSectionSpec("Recent moments", { "$it" }, state.moments, { "m${it.id}" }, { listOfNotNull(it.situation, it.action).joinToString(": ") },
            { Instant.ofEpochMilli(it.createdAt).atZone(ZoneId.systemDefault()).format(DATE) + (it.whenText?.let { w -> ", $w" } ?: "") },
            { }, { vm.deleteMoment(it); deleted { vm.restoreMoment(it) } }),
    )

    Box(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("What I know")
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = SidePadding)) {
                item { Meta("This is what I've understood about you. Correct anything I've got wrong.") }
                if (state.cleaningUp) item { Meta("Cleaning up what I know…") }
                item {
                    PurposeTextField(
                        query, { query = it; vm.query.value = it }, placeholder = "Search what I know", singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }
                if (q.isNotEmpty()) {
                    // Search: every match, from every section (archived too), with its section in meta.
                    val hits = buildList {
                        sections.forEach { spec -> addAll(spec.searchRows(q)) }
                        if (future.any { matches(it.value) }) add(SearchRow("f", future.joinToString(" ") { it.value.trim() }, "Your future self") { editing = Editing.Profile(future.first()) })
                        archivedMatches.forEach { n -> add(SearchRow("ar${n.id}", n.text, "Archived") {}) }
                        state.archivedProfile.filter { matches(it.value, it.key) }.forEach { add(SearchRow("ap${it.key}", it.value, "Archived, ${keyLabel(it.key)}") {}) }
                        state.archivedStrengths.filter { matches(it.text) }.forEach { add(SearchRow("as${it.id}", it.text, "Archived strength") {}) }
                    }
                    if (hits.isEmpty()) item { Meta("Nothing found.", Modifier.padding(top = 24.dp)) }
                    itemsIndexed(hits, key = { _, h -> "q-" + h.key }) { i, h ->
                        if (i > 0) Hairline()
                        ItemRow(h.text, h.section, maxLines = if (h.key in expandedItems) Int.MAX_VALUE else 2) { tapItem(h.key, h.edit) }
                    }
                    item { Spacer(Modifier.height(32.dp)) }
                    return@LazyColumn
                }
                item {
                    SnapshotRow(
                        state, onOpenSnapshot, vm::refreshSnapshot, vm::writeSnapshotNow,
                        onContinue = { vm.continueOnboarding(onOpenOnboarding, onOpenTalk) },
                    )
                }
                if (state.chapters > 0) {
                    item {
                        Row(Modifier.fillMaxWidth().clickable(onClick = onOpenChapters).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Chapters", style = Purpose.type.itemText, color = Purpose.colors.text)
                                Meta(if (state.chapters == 1) "1 chapter of your story" else "${state.chapters} chapters of your story")
                            }
                            Icon(PurposeIcons.Chevron, contentDescription = null, tint = Purpose.colors.textMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                sections.forEachIndexed { index, spec ->
                    // Your future self sits right after About you, as one short paragraph.
                    if (index == 1 && future.isNotEmpty()) {
                        collapsedRow("Your future self", null, "Your future self" in open) { toggle(open, "Your future self") }
                        if ("Your future self" in open) {
                            item(key = "future-text") {
                                ItemRow(future.joinToString(" ") { com.umair.purpose.memory.Corrections.display(it.value).trim() }, confirmed(null, future.any(com.umair.purpose.memory.Corrections::isProtected)), maxLines = Int.MAX_VALUE) { editing = Editing.Profile(future.first()) }
                            }
                        }
                    }
                    if (spec.rows.isEmpty()) return@forEachIndexed
                    collapsedRow(spec.title, spec.count(spec.rows.size), spec.title in open) { toggle(open, spec.title) }
                    if (spec.title in open) spec.items(this, spec.title in showingAll, expandedItems, ::tapItem) { showingAll += spec.title }
                }
                // Archived: everything gardening and the caps tidied away, a page at a time.
                val archivedTotal = state.archivedCount + state.archivedProfile.size + state.archivedStrengths.size
                if (archivedTotal > 0) {
                    collapsedRow("Archived", "$archivedTotal", "Archived" in open) { toggle(open, "Archived") }
                    if ("Archived" in open) {
                        item(key = "archived-note") { Meta("Kept for good and searchable, but not used in conversations.", Modifier.padding(bottom = 8.dp)) }
                        items(state.archivedProfile, key = { "ap${it.key}" }) { e -> ItemRow(e.value, keyLabel(e.key), maxLines = 2) {} }
                        items(state.archivedStrengths, key = { "as${it.id}" }) { st -> ItemRow(st.text, "Strength", maxLines = 2) {} }
                        items(count = archivedPages.itemCount, key = archivedPages.itemKey { "ar${it.id}" }) { i ->
                            val n = archivedPages[i] ?: return@items
                            ItemRow(n.text, null, maxLines = if ("ar${n.id}" in expandedItems) Int.MAX_VALUE else 2) { expandedItems += "ar${n.id}" }
                        }
                    }
                }
                if (state.loaded && state.profile.isEmpty() && state.notes.isEmpty() && state.people.isEmpty()) {
                    item {
                        Column(Modifier.padding(top = 48.dp)) {
                            EmptyRidge()
                            Spacer(Modifier.height(16.dp))
                            Text("This fills in as we talk.", style = Purpose.type.itemText.copy(fontSize = 20.sp, lineHeight = 28.sp), color = Purpose.colors.textMuted)
                        }
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }

    when (val e = editing) {
        null -> Unit
        is Editing.Profile -> EditSheet(
            title = keyLabel(e.entry.key),
            fields = listOf(EditField("What I know", com.umair.purpose.memory.Corrections.display(e.entry.value), singleLine = false)),
            onDismiss = { editing = null },
            onSave = { vm.editProfile(e.entry.key, it[0]); editing = null },
            onDelete = { vm.deleteProfile(e.entry); editing = null },
            extra = { MemorySources(e.entry.sourceSessionIds, e.entry.editedByUser, vm, onOpen = { id -> editing = null; vm.openSource(id, onOpenTalk) }) },
        )
        is Editing.PersonItem -> EditSheet(
            title = e.person.name,
            fields = listOf(
                EditField("Name", e.person.name),
                EditField("Who they are to you", e.person.relation.orEmpty()),
                EditField("Notes", e.person.notes.orEmpty(), singleLine = false),
            ),
            onDismiss = { editing = null },
            onSave = { vm.editPerson(e.person.id, it[0], it[1], it[2]); editing = null },
            onDelete = { vm.deletePerson(e.person); editing = null },
            canSave = { it[0].isNotBlank() },
            extra = { MemorySources(e.person.sourceSessionIds, e.person.editedByUser, vm, onOpen = { id -> editing = null; vm.openSource(id, onOpenTalk) }) },
        )
        is Editing.NoteItem -> EditSheet(
            title = when (e.note.type) {
                "pattern" -> "How you work"
                "what_helps" -> "What works for you"
                "what_doesnt" -> "What doesn't"
                else -> "Still on your mind"
            },
            fields = listOf(EditField("What I noticed", e.note.text, singleLine = false)),
            onDismiss = { editing = null },
            onSave = { vm.editNote(e.note.id, it[0]); editing = null },
            onDelete = { vm.deleteNote(e.note); editing = null },
            extra = {
                Meta(when (e.note.confidence) {
                    "guess" -> "A tentative observation, not a fact about you."
                    "likely" -> "Seen more than once; it may still be wrong."
                    else -> "Supported by the record; you can still correct it."
                })
                Meta("${seen(e.note).replaceFirstChar { it.uppercase() }} · Last seen ${Instant.ofEpochMilli(e.note.lastSeen).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy"))}")
                MemorySources(e.note.sourceSessionIds, e.note.editedByUser, vm, onOpen = { id -> editing = null; vm.openSource(id, onOpenTalk) })
            },
        )
        is Editing.StrengthItem -> EditSheet(
            title = "Your strength",
            fields = listOf(EditField("Strength", e.strength.text, singleLine = false)),
            onDismiss = { editing = null },
            onSave = { vm.editStrength(e.strength.id, it[0]); editing = null },
            onDelete = { vm.deleteStrength(e.strength); editing = null },
            extra = { MemorySources(e.strength.sessionId.toString(), e.strength.editedByUser, vm, onOpen = { id -> editing = null; vm.openSource(id, onOpenTalk) }) },
        )
        is Editing.Area -> {
            var status by remember(e) { mutableStateOf(e.area.status) }
            EditSheet(
                title = AREA_LABELS[e.area.area] ?: e.area.area,
                fields = listOf(EditField("Note", e.area.note.orEmpty(), singleLine = false)),
                onDismiss = { editing = null },
                onSave = { vm.editArea(e.area.area, status, it[0]); editing = null },
                onDelete = { vm.deleteArea(e.area); editing = null },
                canSave = { true },
                extra = { Segmented(AreaStatus.STATUSES.map { it to it }, status, { status = it }) },
            )
        }
    }
}

/** Show only linked, surviving conversations. Unknown provenance is never invented. */
@Composable
private fun MemorySources(ids: String, edited: Boolean, vm: KnowViewModel, onOpen: (Long) -> Unit) {
    var sources by remember(ids) { mutableStateOf<List<com.umair.purpose.data.db.Session>?>(null) }
    androidx.compose.runtime.LaunchedEffect(ids) { sources = vm.sourceConversations(ids) }
    Meta(if (edited) "Corrected by you. Future reflections keep your wording." else "You can correct or delete this. Your changes are final.")
    val rows = sources
    when {
        rows == null -> Meta("Loading source conversations…")
        rows.isEmpty() -> Meta("No linked conversation is available for this memory.")
        else -> {
            Meta("Learned from these conversations (up to the latest five):")
            rows.forEach { s ->
                TextAction("${Instant.ofEpochMilli(s.startedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy"))} · ${s.title ?: "Conversation"}", { onOpen(s.id) })
            }
        }
    }
}

private const val TOP_ITEMS = 5

private fun toggle(list: MutableList<String>, key: String) {
    if (key in list) list.remove(key) else list.add(key)
}

/** A match in search: its text and which section it's from. */
private data class SearchRow(val key: String, val text: String, val section: String, val edit: () -> Unit)

private fun <T> KnowSectionSpec<T>.searchRows(q: String): List<SearchRow> =
    rows.filter { r -> text(r).contains(q, true) || meta(r)?.contains(q, true) == true }
        .map { r -> SearchRow(key(r), text(r), title) { onEdit(r) } }

/** A collapsed section: its heading, a meta count and a chevron; tapping opens it in place. 32dp between rows. */
private fun LazyListScope.collapsedRow(title: String, count: String?, isOpen: Boolean, onToggle: () -> Unit) {
    item(key = "h-$title") {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(top = 32.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = Purpose.type.heading, color = Purpose.colors.text)
            count?.let { Meta(", $it", Modifier.padding(start = 2.dp, top = 6.dp)) }
            Spacer(Modifier.weight(1f))
            Icon(
                PurposeIcons.Chevron, contentDescription = if (isOpen) "Close" else "Open", tint = Purpose.colors.textMuted,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (isOpen) 180f else 0f },
            )
        }
    }
}

/** An open section's rows: the top 5 (or all), hairlines between, swipe to delete, then "Show all (N)". */
private fun <T> KnowSectionSpec<T>.items(
    scope: LazyListScope,
    showAll: Boolean,
    expanded: List<String>,
    tap: (String, () -> Unit) -> Unit,
    onShowAll: () -> Unit,
) {
    val shown = if (showAll) rows else rows.take(TOP_ITEMS)
    scope.itemsIndexed(shown, key = { _, r -> key(r) }) { i, r ->
        if (i > 0) Hairline()
        Swipeable(onDelete = { onDelete(r) }) {
            ItemRow(text(r), meta(r), maxLines = if (key(r) in expanded) Int.MAX_VALUE else 2) { tap(key(r)) { onEdit(r) } }
        }
    }
    if (shown.size < rows.size) {
        scope.item(key = "more-$title") { TextAction("Show all (${rows.size})", onShowAll, color = Purpose.colors.textMuted) }
    }
}

/** Swipe left to delete (with undo). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Swipeable(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val currentDelete by androidx.compose.runtime.rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it == SwipeToDismissBoxValue.EndToStart) {
            currentDelete(); true
        } else false
    })
    SwipeToDismissBox(state = state, enableDismissFromStartToEnd = false, backgroundContent = {}) {
        Box(Modifier.background(Purpose.colors.background)) { content() }
    }
}

/** At most [maxLines] lines (2 in a section, ellipsized), with its meta line under it. */
@Composable
private fun ItemRow(text: String, meta: String?, maxLines: Int = Int.MAX_VALUE, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp)) {
        Text(text, style = Purpose.type.itemText, color = Purpose.colors.text, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        if (!meta.isNullOrBlank()) Meta(meta)
    }
}

/**
 * Always there (QA 8.5). Before the snapshot exists it says how far the getting-to-know-you conversations
 * are, with Continue; from 2 of 5 on he can also have it written now from what's known.
 */
@Composable
private fun SnapshotRow(state: KnowUiState, onOpen: () -> Unit, onRefresh: () -> Unit, onWriteNow: () -> Unit, onContinue: () -> Unit) {
    val s = state.snapshot
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = s != null, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Your snapshot", style = Purpose.type.itemText, color = Purpose.colors.text)
                Meta(
                    when {
                        state.writingSnapshot -> "Writing your snapshot…"
                        s != null -> s.title
                        state.onboardingDone -> "Ready to be written."
                        else -> "Finish getting to know you (${state.topicsDone} of 5)"
                    }
                )
            }
            if (s != null) {
                if (state.canRefresh && !state.writingSnapshot) TextAction("Refresh", onRefresh, color = Purpose.colors.textMuted)
                else Icon(PurposeIcons.Chevron, contentDescription = null, tint = Purpose.colors.textMuted, modifier = Modifier.size(18.dp))
            }
        }
        if (s == null && !state.writingSnapshot) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!state.onboardingDone) TextAction("Continue", onContinue, Modifier.padding(end = 8.dp))
                if (state.topicsDone >= 2 || state.onboardingDone) {
                    TextAction(if (state.onboardingDone) "Write it now" else "Write it now with what I know", onWriteNow, color = Purpose.colors.textMuted)
                }
            }
        }
    }
}

@HiltViewModel
class SnapshotViewModel @Inject constructor(db: PurposeDatabase) : ViewModel() {
    val snapshot: StateFlow<Snapshot?> = db.snapshotDao().observeLatest()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** Same layout as the letter view. */
@Composable
fun SnapshotScreen(onBack: () -> Unit, vm: SnapshotViewModel = hiltViewModel()) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        ScreenHeader("", onBack = onBack)
        val s = snapshot ?: return@Column
        SelectionContainer {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                Meta("Your snapshot")
                Spacer(Modifier.height(8.dp))
                Text(s.title, style = Purpose.type.openingLine, color = Purpose.colors.text)
                Spacer(Modifier.height(24.dp))
                letterBlocks(s.portrait, sections = true).forEach { (isTitle, text) ->
                    if (isTitle) Text(text, style = Purpose.type.label, color = Purpose.colors.textMuted, modifier = Modifier.padding(top = 32.dp, bottom = 8.dp))
                    else Text(text, style = Purpose.type.letterBody, color = Purpose.colors.text, modifier = Modifier.padding(bottom = 16.dp))
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
