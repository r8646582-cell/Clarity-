package com.umair.purpose.ui.talk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import com.umair.purpose.chat.LiveText
import com.umair.purpose.ui.theme.Purpose
import kotlinx.coroutines.flow.first

/** Words revealed in the last [FADE_MS], still fading in: [start] until [end] in the text, at [alpha]. */
@Immutable
data class Fade(val start: Int, val end: Int, val alpha: Float)

/** What a coach message shows right now: a prefix of the received text, the newest words still fading in. */
@Immutable
data class Revealed(val text: String, val fades: List<Fade> = emptyList())

private const val FADE_MS = 120L
private const val FADE_NANOS = FADE_MS * 1_000_000

/**
 * UPDATE-11 "Smooth reveal". [stored] is the database copy; while this message is the reply on its way, the text
 * comes from [live] instead (every chunk, in memory). A frame loop shows it at an even pace ([RevealPacer]) and
 * fades the newest words in. Only a message that was streaming when it first appeared animates: anything
 * already complete (reopening a chat) shows in full at once. With system animations off, text shows as it arrives.
 *
 * [live] is only read inside effects, never during composition, so a chunk arriving doesn't recompose anything;
 * only the revealed text changing does, once a frame at most, and only for this message.
 */
@Composable
fun rememberRevealed(
    messageId: Long,
    stored: String,
    streaming: Boolean,
    live: State<LiveText?>,
    startEmpty: Boolean,
    reduceMotion: Boolean,
): Revealed {
    val tracked = remember(messageId) { streaming }
    if (!tracked) return Revealed(stored)

    val storedNow by rememberUpdatedState(stored)
    val streamingNow by rememberUpdatedState(streaming)
    fun target(): Pair<String, Boolean> {
        val l = live.value?.takeIf { it.messageId == messageId }
        return (l?.text ?: storedNow) to (!streamingNow || l?.done == true)
    }

    // Keyed on reduceMotion too: the pacer in the effect below restarts from revealed.text.length, so if the
    // setting flips mid-reply the drawn text and the pacer must reset together or they drift apart.
    var revealed by remember(messageId, reduceMotion) {
        val start = if (startEmpty || reduceMotion) "" else Snapshot.withoutReadObservation { target().first }
        mutableStateOf(Revealed(start))
    }

    LaunchedEffect(messageId, reduceMotion) {
        val pacer = RevealPacer(revealed.text.length)
        val fades = ArrayList<Triple<Int, Int, Long>>()
        while (true) {
            if (reduceMotion) {
                // Animations off: whatever has arrived shows at once.
                snapshotFlow { target().first }.collect { revealed = Revealed(it) }
                return@LaunchedEffect
            }
            // Idle (no frames) until there's something new to show.
            snapshotFlow { target() }.first { (text, done) -> pacer.hasWork(text, done) }
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                val dt = if (last == 0L) 1.0 / 60 else (now - last) / 1e9
                last = now
                val (text, done) = target()
                val before = pacer.shown
                val shown = pacer.step(text, done, dt)
                if (shown < before) fades.clear() else if (shown > before) fades += Triple(before, shown, now)
                fades.removeAll { now - it.third >= FADE_NANOS }
                revealed = Revealed(
                    text.take(shown),
                    fades.map { (a, b, t) -> Fade(a, b, ((now - t).toFloat() / FADE_NANOS).coerceIn(0f, 1f)) },
                )
                if (fades.isEmpty() && !pacer.hasWork(text, done)) break
            }
        }
    }
    return revealed
}

/**
 * A coach message's text: coachBody 18/28sp, 14dp between paragraphs (DESIGN.md reading comfort). One Text per
 * paragraph, so while a reply streams only the last paragraph changes and earlier ones aren't laid out again.
 */
@Composable
fun CoachParagraphs(revealed: Revealed, modifier: Modifier = Modifier) {
    val color = Purpose.colors.text
    val text = revealed.text
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        RevealPacer.paragraphs(text).forEach { range ->
            val fading = revealed.fades.filter { it.end > range.first && it.start <= range.last }
            val para = if (fading.isEmpty()) {
                AnnotatedString(text.substring(range))
            } else {
                buildAnnotatedString {
                    append(text.substring(range))
                    fading.forEach { f ->
                        val from = (f.start - range.first).coerceAtLeast(0)
                        val to = (f.end - range.first).coerceAtMost(range.last - range.first + 1)
                        if (to > from) addStyle(SpanStyle(color = color.copy(alpha = color.alpha * f.alpha)), from, to)
                    }
                }
            }
            Text(para, style = Purpose.type.coachBody, color = color)
        }
    }
}
