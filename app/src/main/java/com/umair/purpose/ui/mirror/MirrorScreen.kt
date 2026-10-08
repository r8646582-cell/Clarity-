package com.umair.purpose.ui.mirror

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.umair.purpose.data.db.Letter
import com.umair.purpose.ui.common.ConfirmDialog
import com.umair.purpose.ui.common.EmptyRidge
import com.umair.purpose.ui.common.Hairline
import com.umair.purpose.ui.common.MenuAction
import com.umair.purpose.ui.common.Meta
import com.umair.purpose.ui.common.OverflowMenu
import com.umair.purpose.ui.common.ScreenHeader
import com.umair.purpose.ui.common.SidePadding
import com.umair.purpose.ui.common.TextAction
import com.umair.purpose.ui.theme.Purpose
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM")
private val MONTH = DateTimeFormatter.ofPattern("MMMM")

/** "Weekly letter, 20 to 26 Oct" */
fun letterMeta(l: Letter): String {
    val s = LocalDate.parse(l.periodStart)
    val e = LocalDate.parse(l.periodEnd)
    val range = if (s.month == e.month) "${s.dayOfMonth} to ${e.format(DAY_MONTH)}" else "${s.format(DAY_MONTH)} to ${e.format(DAY_MONTH)}"
    return when (l.kind) {
        Letter.WEEKLY -> "Weekly letter, $range"
        Letter.MONTHLY -> "Monthly letter, $range"
        else -> "Yearly letter, ${s.year}"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MirrorScreen(onOpen: (Long) -> Unit, onOpenChapter: (Long) -> Unit = {}, vm: MirrorViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pages = vm.pages.collectAsLazyPagingItems()
    var deleting by remember { mutableStateOf<Letter?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.refreshCanWrite()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        ScreenHeader("Mirror") {
            OverflowMenu(
                listOf(
                    MenuAction(
                        if (state.writing) "Writing this week's letter…" else "Write this week's letter now",
                        enabled = state.canWriteNow && !state.writing,
                        note = if (!state.canWriteNow && !state.writing) "Needs at least one real conversation this week first." else null,
                        onClick = vm::writeNow,
                    )
                )
            )
        }
        // Never a raw error here: one quiet line and a way to try again.
        state.delayed?.let { kind ->
            Row(Modifier.fillMaxWidth().padding(start = SidePadding, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Meta(delayedText(kind), Modifier.weight(1f))
                TextAction("Try again", vm::tryAgain)
            }
        }
        if (state.writing) Meta("Writing this week's letter…", Modifier.padding(horizontal = SidePadding))
        if (state.loaded && state.letterCount == 0 && state.chapters.isEmpty() && state.delayed == null) {
            EmptyRidge(Modifier.padding(start = SidePadding, top = 48.dp))
            Text(
                "Your first letter arrives on Sunday evening, once we've talked. It'll show you what I'm noticing about you.",
                style = Purpose.type.itemText.copy(fontSize = 20.sp, lineHeight = 28.sp),
                color = Purpose.colors.textMuted,
                modifier = Modifier.padding(start = SidePadding, end = SidePadding, top = 16.dp, bottom = 48.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            // UPDATE-15: the chapters of his story, letter style, above the letters.
            if (state.chapters.isNotEmpty()) {
                item(key = "chapters") {
                    Text("Chapters", style = Purpose.type.heading, color = Purpose.colors.text, modifier = Modifier.padding(horizontal = SidePadding, vertical = 12.dp))
                }
                items(state.chapters, key = { "c${it.id}" }) { c ->
                    Column {
                        Hairline()
                        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenChapter(c.id) }).padding(horizontal = SidePadding, vertical = 16.dp)) {
                            UnreadDot(c.readAt == null, Modifier.padding(top = 10.dp))
                            Column {
                                Text(c.title, style = Purpose.type.itemText, color = Purpose.colors.text)
                                Meta(chapterMeta(c))
                            }
                        }
                    }
                }
                item(key = "chapters_end") { Hairline(); Spacer(Modifier.height(16.dp)) }
            }
            items(count = pages.itemCount, key = pages.itemKey { it.id }) { index ->
                val l = pages[index] ?: return@items
                val unread = l.readAt == null
                val rowMod = Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(l.id) }, onLongClick = { deleting = l })
                if (l.kind == Letter.WEEKLY) {
                    Row(rowMod.padding(horizontal = SidePadding, vertical = 10.dp)) {
                        UnreadDot(unread, Modifier.padding(top = 9.dp))
                        Column {
                            Text("Week of ${LocalDate.parse(l.periodEnd).format(DAY_MONTH)}", style = Purpose.type.itemText, color = Purpose.colors.text)
                            Text(l.title, style = Purpose.type.meta.copy(fontSize = 15.sp, lineHeight = 21.sp), color = Purpose.colors.textMuted)
                        }
                    }
                } else {
                    Column {
                        Hairline()
                        Row(rowMod.padding(horizontal = SidePadding, vertical = 20.dp)) {
                            UnreadDot(unread, Modifier.padding(top = 12.dp))
                            Column {
                                val start = LocalDate.parse(l.periodStart)
                                Text(
                                    if (l.kind == Letter.MONTHLY) start.format(MONTH) else start.year.toString(),
                                    style = Purpose.type.heading.copy(fontSize = 24.sp, lineHeight = 30.sp),
                                    color = Purpose.colors.text,
                                )
                                Text(l.title, style = Purpose.type.itemText, color = Purpose.colors.textMuted)
                                Meta(letterMeta(l))
                            }
                        }
                        Hairline()
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    deleting?.let { l ->
        ConfirmDialog(
            text = "Delete this letter? This can't be undone.",
            confirm = "Delete",
            onConfirm = { vm.delete(l.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

/** "Chapter, July to September 2026" */
fun chapterMeta(c: com.umair.purpose.data.db.Chapter): String {
    val s = LocalDate.parse(c.periodStart)
    val e = LocalDate.parse(c.periodEnd)
    return "Chapter, ${s.format(MONTH)} to ${e.format(MONTH)} ${e.year}"
}

/** UPDATE-15: every chapter, from What I know's "Chapters" row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChaptersScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, vm: MirrorViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        ScreenHeader("Chapters", onBack = onBack)
        Meta("Every three months, a chapter of your story. They're what I carry forward for years.", Modifier.padding(horizontal = SidePadding))
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            items(state.chapters, key = { it.id }) { c ->
                Column(Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(c.id) }).padding(horizontal = SidePadding, vertical = 14.dp)) {
                    Text(c.title, style = Purpose.type.itemText, color = Purpose.colors.text)
                    Meta(chapterMeta(c))
                }
                Hairline()
            }
        }
    }
}

/** UPDATE-15: a life chapter, read like a letter. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChapterScreen(onBack: () -> Unit, vm: ChapterViewModel = hiltViewModel()) {
    val chapter by vm.chapter.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        ScreenHeader("", onBack = onBack)
        val c = chapter ?: return@Column
        SelectionContainer {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 28.dp)) {
                Meta(chapterMeta(c))
                Spacer(Modifier.height(8.dp))
                Text(c.title, style = Purpose.type.openingLine, color = Purpose.colors.text)
                Spacer(Modifier.height(24.dp))
                letterBlocks(c.content, sections = true).forEach { (isTitle, text) ->
                    if (isTitle) Text(text, style = Purpose.type.label, color = Purpose.colors.textMuted, modifier = Modifier.padding(top = 32.dp, bottom = 8.dp))
                    else Text(text, style = Purpose.type.letterBody, color = Purpose.colors.text, modifier = Modifier.padding(bottom = 16.dp))
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private fun delayedText(kind: String) = when (kind) {
    Letter.MONTHLY -> "This month's letter is delayed."
    Letter.YEARLY -> "This year's letter is delayed."
    else -> "This week's letter is delayed."
}

@Composable
private fun UnreadDot(unread: Boolean, modifier: Modifier = Modifier) {
    Box(Modifier.width(18.dp)) {
        if (unread) Box(modifier.size(6.dp).clip(CircleShape).background(Purpose.colors.accent))
    }
}

/** A line on its own, short and without closing punctuation, reads as a section title in a monthly letter. */
internal fun isSectionTitle(paragraph: String): Boolean {
    val p = paragraph.trim()
    return !p.contains('\n') && p.length in 2..60 && p.last() !in ".!?\"'”’:,;"
}

/** Paragraphs, with section titles split out (a title may sit directly above its paragraph). */
internal fun letterBlocks(content: String, sections: Boolean): List<Pair<Boolean, String>> =
    content.split(Regex("""\n\s*\n""")).filter { it.isNotBlank() }.flatMap { para ->
        val lines = para.trim().lines()
        when {
            !sections -> listOf(false to para.trim())
            lines.size == 1 && isSectionTitle(lines[0]) -> listOf(true to lines[0].trim())
            lines.size > 1 && isSectionTitle(lines[0]) -> listOf(true to lines[0].trim(), false to lines.drop(1).joinToString("\n").trim())
            else -> listOf(false to para.trim())
        }
    }

@Composable
fun LetterScreen(onBack: () -> Unit, onTalk: () -> Unit, vm: LetterViewModel = hiltViewModel()) {
    val letter by vm.letter.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        ScreenHeader("", onBack = onBack) {
            OverflowMenu(listOf(MenuAction("Delete letter") { confirming = true }))
        }
        val l = letter ?: return@Column
        SelectionContainer {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 28.dp)) {
                Meta(letterMeta(l))
                Spacer(Modifier.height(8.dp))
                Text(l.title, style = Purpose.type.openingLine, color = Purpose.colors.text)
                Spacer(Modifier.height(24.dp))
                letterBlocks(l.content, sections = l.kind != Letter.WEEKLY).forEach { (isTitle, text) ->
                    if (isTitle) {
                        Text(text, style = Purpose.type.label, color = Purpose.colors.textMuted, modifier = Modifier.padding(top = 32.dp, bottom = 8.dp))
                    } else {
                        Text(text, style = Purpose.type.letterBody, color = Purpose.colors.text, modifier = Modifier.padding(bottom = 16.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextAction("Talk about this letter", { vm.talkAbout(onTalk) })
                }
            }
        }
    }
    if (confirming) {
        ConfirmDialog(
            text = "Delete this letter? This can't be undone.",
            confirm = "Delete",
            onConfirm = { confirming = false; vm.delete(onBack) },
            onDismiss = { confirming = false },
        )
    }
}
