package com.umair.purpose.chat

import com.umair.purpose.chat.ModelRouter.Input
import com.umair.purpose.chat.ModelRouter.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRouterTest {
    private val earlier = listOf("hey")

    private fun route(message: String, earlier: List<String> = this.earlier, mode: Boolean = false) =
        ModelRouter.route(Input(message, earlier, modeActive = mode))

    @Test
    fun heavyEnglishGoesDeep() {
        val r = route("I'm wasting my time and don't know if I'm enough")
        assertEquals(Tier.DEEP, r.tier)
        assertEquals(Reason.HEAVY, r.reason)
        assertEquals(Tier.DEEP, route("Honestly I feel so alone lately").tier)
        assertEquals(Tier.DEEP, route("I HATE MYSELF for this").tier)
    }

    @Test
    fun parentNamesGoDeep() {
        assertEquals(Tier.DEEP, route("Abbu and I didn't talk again today").tier)
    }

    @Test
    fun shortCasualGoesFast() {
        val r = route("ok cool, thanks")
        assertEquals(Tier.FAST, r.tier)
        assertEquals(Reason.CASUAL, r.reason)
    }

    @Test
    fun wholeWordsOnly() {
        // "dar" inside "andar", "cry" inside "crystal", "fail" inside "failsafe".
        assertFalse(ModelRouter.isHeavy("andar aa jao"))
        assertFalse(ModelRouter.isHeavy("crystal clear"))
        assertFalse(ModelRouter.isHeavy("a failsafe plan"))
        assertTrue(ModelRouter.isHeavy("I failed the mock"))
    }

    @Test
    fun longMessageGoesDeep() {
        val r = route("a".repeat(ModelRouter.LONG_MESSAGE + 1))
        assertEquals(Tier.DEEP, r.tier)
        assertEquals(Reason.LONG, r.reason)
    }

    @Test
    fun activeModeGoesDeep() {
        val r = route("ok", mode = true)
        assertEquals(Tier.DEEP, r.tier)
        assertEquals(Reason.MODE, r.reason)
    }

    @Test
    fun sessionOpenerIsRightSized() {
        // A plain opener does not need the Pro model with thinking: CLAUDE.md "Cost efficiency → Right-size
        // routing" — the first message goes Deep only if it is also long, heavy or in a mode.
        val r = route("hi", earlier = emptyList())
        assertEquals(Tier.FAST, r.tier)
        assertEquals(Reason.CASUAL, r.reason)
        assertEquals(Reason.LONG, route("a".repeat(ModelRouter.LONG_MESSAGE + 1), earlier = emptyList()).reason)
        assertEquals(Reason.HEAVY, route("I feel so alone", earlier = emptyList()).reason)
        assertEquals(Reason.MODE, route("ok", earlier = emptyList(), mode = true).reason)
        assertEquals(Reason.ALWAYS_DEEP, ModelRouter.route(Input("hi", emptyList(), alwaysDeep = true)).reason)
    }

    @Test
    fun staysDeepForThreeMessagesAfterAHeavyOne() {
        val heavy = "I feel worthless"
        assertEquals(Reason.STAYING_DEEP, route("ok", earlier = listOf("hi", heavy)).reason)
        assertEquals(Reason.STAYING_DEEP, route("ok", earlier = listOf("hi", heavy, "a", "b")).reason)
        assertEquals(Tier.FAST, route("ok", earlier = listOf("hi", heavy, "a", "b", "c")).tier)
    }

    @Test
    fun firstMessageAloneDoesNotKeepItDeep() {
        assertEquals(Tier.FAST, route("ok", earlier = listOf("hi")).tier)
    }

    @Test
    fun settingsOverride() {
        assertEquals(Tier.DEEP, ModelRouter.route(Input("ok", earlier, alwaysDeep = true)).tier)
        // The budget guard wins over everything, even a heavy message.
        assertEquals(Tier.FAST, ModelRouter.route(Input("I feel worthless", earlier, alwaysDeep = true, overBudget = true)).tier)
    }
}
