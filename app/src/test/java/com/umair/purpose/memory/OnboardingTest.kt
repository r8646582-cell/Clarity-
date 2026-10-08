package com.umair.purpose.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    @Test
    fun `fifty Factor Markers items, ten per trait, with their keying, interleaved`() {
        assertEquals(50, BigFive.ITEMS.size)
        assertEquals(50, BigFive.ITEMS.map { it.text }.toSet().size)
        // Positively keyed per factor in the IPIP Factor Markers (N is scored as Neuroticism: 8 of 10).
        val plus = mapOf('E' to 5, 'A' to 6, 'C' to 6, 'N' to 8, 'O' to 7)
        BigFive.DOMAINS.forEach { d ->
            val items = BigFive.ITEMS.filter { it.domain == d }
            assertEquals(10, items.size)
            assertEquals("keying for $d", plus.getValue(d), items.count { it.plus })
        }
        assertEquals(listOf('E', 'A', 'C', 'N', 'O'), BigFive.ITEMS.take(5).map { it.domain })
        assertEquals("Am the life of the party", BigFive.ITEMS[0].text)
    }

    @Test
    fun `no item about politics or religion`() {
        assertTrue(BigFive.ITEMS.none { BigFive.offLimits(it) })
        assertTrue(BigFive.ITEMS.none { it.text.contains("vote", true) || it.text.contains("political", true) })
        assertEquals(2, BigFive.LEGACY_ITEMS.count { BigFive.offLimits(it) })
    }

    @Test
    fun `old answers drop the voting items and are rescored`() {
        // Agree with everything on the old list: the two voting items (one +O, one -O) would cancel out.
        val old = BigFiveData(BigFive.LEGACY_ITEMS.map { if (it.plus) 5 else 1 }, mapOf("O" to 1))
        val fixed = BigFive.rescoreLegacy(old)!!
        assertEquals(100, fixed.percents["O"])
        BigFive.LEGACY_ITEMS.forEachIndexed { i, item -> if (BigFive.offLimits(item)) assertEquals(0, fixed.answers[i]) }
        assertNull(BigFive.rescoreLegacy(BigFiveData(emptyList(), emptyMap(), itemSet = BigFive.ITEM_SET)))
        // A draft from the old list can't be continued on the new one.
        val oldDraft = OnboardingFormat.encodeBigFiveDraft(BigFiveDraft(listOf(3, 4), 1))
        assertEquals(emptyList<Int>(), OnboardingFormat.decodeBigFiveDraft(oldDraft).answers)
        val newDraft = OnboardingFormat.encodeBigFiveDraft(BigFiveDraft(listOf(3, 4), 1, BigFive.ITEM_SET))
        assertEquals(listOf(3, 4), OnboardingFormat.decodeBigFiveDraft(newDraft).answers)
    }

    @Test
    fun `scores are percentages, reverse items flipped`() {
        // Agree with every positively keyed item, disagree with every reversed one: 100% everywhere.
        val max = BigFive.ITEMS.map { if (it.plus) 5 else 1 }
        assertEquals(mapOf("O" to 100, "C" to 100, "E" to 100, "A" to 100, "N" to 100), BigFive.score(max))
        val neutral = BigFive.ITEMS.map { 3 }
        assertEquals(50, BigFive.score(neutral)["C"])
        // A trait with fewer than half its items answered is left out.
        val oIndexes = BigFive.ITEMS.indices.filter { BigFive.ITEMS[it].domain == 'O' }
        val fourAnswered = BigFive.ITEMS.indices.map { i -> if (i in oIndexes.drop(4)) 0 else 3 }
        assertNull(BigFive.score(fourAnswered)["O"])
        val fiveAnswered = BigFive.ITEMS.indices.map { i -> if (i in oIndexes.drop(5)) 0 else 3 }
        assertEquals(50, BigFive.score(fiveAnswered)["O"])
    }

    @Test
    fun `formats for prompts`() {
        val json = OnboardingFormat.encodeBigFive(BigFiveData(listOf(1, 2), mapOf("O" to 62, "N" to 58)))
        assertEquals("Openness 62%, Neuroticism 58%", OnboardingFormat.bigFiveLine(json))
        assertEquals("family, faith", OnboardingFormat.valuesLine(OnboardingFormat.encodeValues(listOf("family", "faith"))))
        assertNull(OnboardingFormat.bigFiveLine("not json"))
        assertNull(OnboardingFormat.valuesLine(null))
        assertEquals(30, VALUE_CHOICES.size)
    }
}
