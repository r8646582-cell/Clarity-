package com.umair.purpose.ui.talk

/**
 * UPDATE-11 "Smooth reveal": how much of a streaming reply to show, frame by frame. Pure Kotlin, so it's tested
 * without Android.
 *
 * The received text arrives in irregular bursts; the display moves at an even pace instead. About
 * [BASE_RATE] characters a second, faster when the backlog grows so the display is never more than about
 * [MAX_LAG_S] behind, and once the stream is done whatever is left is finished within about [FINISH_S].
 * It always stops at whole words: while the stream runs, the last word may still be arriving, so it waits.
 */
class RevealPacer(initialShown: Int = 0) {
    /** Characters of the received text on screen. */
    var shown: Int = initialShown
        private set

    private var progress = initialShown.toDouble()
    private var finishRate = 0.0

    /** Characters that may be shown: all of it once the stream is done, otherwise up to the last complete word. */
    fun available(text: String, done: Boolean): Int {
        if (done) return text.length
        val lastSpace = text.indexOfLast { it.isWhitespace() }
        return if (lastSpace < 0) 0 else lastSpace
    }

    /** More to show, or the text shrank under us (the silent Fast fallback starts over). */
    fun hasWork(text: String, done: Boolean): Boolean = text.length < shown || shown < available(text, done)

    /** Advances by [dtSeconds] of wall time; returns the new [shown]. */
    fun step(text: String, done: Boolean, dtSeconds: Double): Int {
        if (text.length < shown) reset()
        val avail = available(text, done)
        if (shown >= avail) {
            progress = progress.coerceAtMost(shown.toDouble())
            return shown
        }
        val backlog = avail - progress
        // The coach may set a cadence (set_cadence): speed scales the whole reveal, default 1.0 is unchanged.
        var rate = maxOf(BASE_RATE, backlog / MAX_LAG_S) * speed.coerceIn(MIN_SPEED, MAX_SPEED)
        if (done) {
            // Fixed when the stream ends, so the rest goes out evenly rather than slowing down to the end.
            if (finishRate == 0.0) finishRate = backlog / FINISH_S
            rate = maxOf(rate, finishRate)
        }
        progress = (progress + rate * dtSeconds.coerceIn(0.0, MAX_STEP_S)).coerceAtMost(avail.toDouble())
        shown = maxOf(shown, wordEnd(text, progress.toInt(), avail))
        return shown
    }

    /** Shows everything there is right away (system animations off). */
    fun jumpTo(length: Int) {
        shown = length
        progress = length.toDouble()
    }

    private fun reset() {
        shown = 0
        progress = 0.0
        finishRate = 0.0
    }

    companion object {
        const val BASE_RATE = 60.0
        const val MAX_LAG_S = 0.4
        const val FINISH_S = 0.45
        /** A long frame (a hitch, or the app coming back) catches up gently instead of in one jump. */
        const val MAX_STEP_S = 0.1

        /**
         * Live reveal speed, set autonomously by the coach's `set_cadence` action (see ActionExecutor) and restored
         * from UiPrefs on launch. 1.0 is the default and leaves the pacing exactly as before.
         */
        @Volatile var speed: Double = 1.0
        const val MIN_SPEED = 0.5
        const val MAX_SPEED = 2.0

        /** From [from], the end of the word it falls in (never past [limit]). */
        fun wordEnd(text: String, from: Int, limit: Int): Int {
            var i = from.coerceIn(0, limit)
            while (i < limit && !text[i].isWhitespace()) i++
            return i
        }

        /**
         * The paragraphs of a coach message (split at blank lines, trimmed, blank ones skipped), as ranges into
         * [text], so the newest words can be found in them. Earlier paragraphs stay the same as text streams in.
         */
        fun paragraphs(text: String): List<IntRange> {
            val out = ArrayList<IntRange>()
            var start = 0
            fun add(from: Int, to: Int) {
                var a = from
                var b = to
                while (a < b && text[a].isWhitespace()) a++
                while (b > a && text[b - 1].isWhitespace()) b--
                if (b > a) out += a until b
            }
            for (m in PARAGRAPH_BREAK.findAll(text)) {
                add(start, m.range.first)
                start = m.range.last + 1
            }
            add(start, text.length)
            return out
        }

        private val PARAGRAPH_BREAK = Regex("""\n\s*\n""")
    }
}
