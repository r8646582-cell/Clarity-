package com.umair.purpose.cost

import com.umair.purpose.ai.AiDefaults
import com.umair.purpose.ai.AiJob
import com.umair.purpose.ai.JobModelChoice
import com.umair.purpose.ai.JobModels
import com.umair.purpose.ai.ModelSource
import com.umair.purpose.data.repo.BackupProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JobModelsTest {
    private val ai = AiDefaults.DEEPSEEK
    private val claude = BackupProvider(BackupProvider.ANTHROPIC, "https://api.anthropic.com", "claude-haiku", "claude-opus")
    private val other = BackupProvider(BackupProvider.OPENAI, "https://x", "small", "big")

    @Test
    fun `no choice is exactly what the app did before`() {
        val r = JobModels.resolve(JobModelChoice(), ai, claude)
        assertEquals(ai.deepModel, r.model)
        assertFalse(r.useBackup)
        assertNull(r.prices)
    }

    @Test
    fun `main fast and main deep use the main provider's models`() {
        assertEquals(ai.chatModel, JobModels.resolve(JobModelChoice(ModelSource.MAIN_FAST), ai, null).model)
        assertEquals(ai.deepModel, JobModels.resolve(JobModelChoice(ModelSource.MAIN_DEEP), ai, null).model)
    }

    @Test
    fun `backup deep routes to the backup client with its price`() {
        val p = Prices(1.0, 15.0, 75.0)
        val r = JobModels.resolve(JobModelChoice(ModelSource.BACKUP_DEEP, prices = p), ai, claude)
        assertEquals("claude-opus", r.model)
        assertTrue(r.useBackup)
        assertEquals(p, r.prices)
    }

    @Test
    fun `a choice that cannot be honoured falls back to the main deep model`() {
        assertEquals(ai.deepModel, JobModels.resolve(JobModelChoice(ModelSource.BACKUP_DEEP), ai, null).model)
        assertFalse(JobModels.resolve(JobModelChoice(ModelSource.BACKUP_DEEP), ai, null).useBackup)
        assertEquals(ai.deepModel, JobModels.resolve(JobModelChoice(ModelSource.CUSTOM, "  "), ai, null).model)
    }

    @Test
    fun `custom model is trimmed and stays on the main provider`() {
        val r = JobModels.resolve(JobModelChoice(ModelSource.CUSTOM, " my-model "), ai, claude)
        assertEquals("my-model", r.model)
        assertFalse(r.useBackup)
        assertNull(r.prices)
    }

    @Test
    fun `unknown prices are not passed on`() {
        assertNull(JobModels.resolve(JobModelChoice(ModelSource.BACKUP_DEEP, prices = Prices(0.0, 0.0, 0.0)), ai, claude).prices)
    }

    @Test
    fun `the strongest suggestion is the backup only when it is Anthropic`() {
        assertEquals(ModelSource.BACKUP_DEEP, JobModels.strongest(claude))
        assertEquals(ModelSource.MAIN_DEEP, JobModels.strongest(other))
        assertEquals(ModelSource.MAIN_DEEP, JobModels.strongest(null))
    }

    private fun use(purpose: String, model: String, hit: Long, miss: Long, out: Long, off: Boolean = false) =
        FeatureUsage(purpose, null, model, off, 1, hit + miss, hit, miss, out)

    private val pricing = Pricing("flash", Prices(0.01, 0.4, 1.2), "pro", Prices(0.04, 1.2, 3.6), offPeakFactor = 0.5)

    @Test
    fun `actual cost counts only that job's purposes`() {
        val usage = listOf(use("reflection", "pro", 0, 1_000_000, 0), use("chat", "flash", 0, 1_000_000, 0), use("letter", "pro", 0, 0, 1_000_000))
        val line = JobCosts.actual(AiJob.REFLECTION, usage, pricing)
        assertEquals(1.2, line.actualUsd, 1e-9)
        assertTrue(line.complete)
        assertEquals(3.6, JobCosts.actual(AiJob.LETTERS, usage, pricing).actualUsd, 1e-9)
    }

    @Test
    fun `usage on a model with no price makes the estimate incomplete until a price is entered`() {
        val usage = listOf(use("letter", "claude-opus", 0, 1_000_000, 0))
        assertFalse(JobCosts.actual(AiJob.LETTERS, usage, pricing).complete)
        val priced = pricing.copy(extra = mapOf("claude-opus" to Prices(1.0, 15.0, 75.0)))
        val line = JobCosts.actual(AiJob.LETTERS, usage, priced)
        assertTrue(line.complete)
        assertEquals(15.0, line.actualUsd, 1e-9)
    }

    @Test
    fun `projection reprices the same tokens and keeps the off-peak discount`() {
        val usage = listOf(use("reflection", "pro", 0, 1_000_000, 0), use("reflection", "pro", 0, 1_000_000, 0, off = true))
        val p = JobCosts.projected(AiJob.REFLECTION, usage, Prices(1.0, 10.0, 50.0), 0.5)
        assertEquals(15.0, p!!, 1e-9)
        assertNull(JobCosts.projected(AiJob.REFLECTION, usage, null, 0.5))
        assertNull(JobCosts.projected(AiJob.SNAPSHOT, usage, Prices(1.0, 10.0, 50.0), 0.5))
    }
}
