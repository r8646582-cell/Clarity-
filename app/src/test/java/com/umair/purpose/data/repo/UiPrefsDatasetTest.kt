package com.umair.purpose.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class UiPrefsDatasetTest {
    @Test fun `replacement clears reused-id state and keeps device preferences`() {
        val prefs = UiPrefs(ApplicationProvider.getApplicationContext<Context>())
        prefs.voiceName = "chosen voice"
        prefs.suggestionsJson = "old suggestion"
        prefs.suggestionsAt = 100
        prefs.morningPerspective = "old promises"
        prefs.setStepDone(StepDoneCard(1, 2, "story"))
        prefs.dismiss("journey", "2026-10-06")
        prefs.giveUpOnReflection(1)
        prefs.recordReflectionFailure(2)
        prefs.setLetterDelayed("weekly")
        prefs.recordJob("reflection", true, 100)
        prefs.datasetReplaced()
        assertEquals("chosen voice", prefs.voiceName)
        assertNull(prefs.suggestionsJson)
        assertEquals(0L, prefs.suggestionsAt)
        assertNull(prefs.morningPerspective)
        assertNull(prefs.stepDone.value)
        assertTrue(prefs.dismissed.value.isEmpty())
        assertFalse(prefs.reflectionGaveUp(1))
        assertEquals(0, prefs.reflectionFailures(2))
        assertNull(prefs.letterDelayed.value)
        assertNull(prefs.job("reflection").lastRunAt)
        // Reopening preferences after process death does not resurrect old identity state.
        assertNull(UiPrefs(ApplicationProvider.getApplicationContext<Context>()).stepDone.value)
    }
}
