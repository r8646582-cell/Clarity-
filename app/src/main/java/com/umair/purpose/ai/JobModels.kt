package com.umair.purpose.ai

import com.umair.purpose.cost.Prices
import com.umair.purpose.data.repo.BackupProvider

/**
 * Phase 5: the slow jobs that are written rarely and shape memory for years. Each can use its own model; chat keeps
 * the fast/deep routing. [purpose] is the name the job's usage is logged under, for the cost estimate.
 */
enum class AiJob(val wire: String, val label: String, val purposes: Set<String>) {
    REFLECTION("reflection", "Reflection", setOf("reflection")),
    LETTERS("letters", "Weekly, monthly and yearly letters", setOf("letter")),
    CHAPTER("chapter", "Life chapters", setOf("chapter")),
    GARDENING("gardening", "Memory gardening", setOf("gardening")),
    SNAPSHOT("snapshot", "Snapshot", setOf("snapshot"));

    companion object {
        fun fromWire(v: String?) = entries.firstOrNull { it.wire == v }
    }
}

/** Where a job's model comes from. */
enum class ModelSource(val wire: String, val label: String) {
    /** What the app did before Phase 5: the main provider's deep model. */
    DEFAULT("default", "Same as before (main deep model)"),
    MAIN_DEEP("main_deep", "Main provider, deep model"),
    MAIN_FAST("main_fast", "Main provider, fast model"),
    BACKUP_DEEP("backup_deep", "Backup provider's deep model"),
    CUSTOM("custom", "A model name on the main provider");

    companion object {
        fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: DEFAULT
    }
}

/**
 * One job's choice. [prices] are optional USD per 1M tokens for a model the app has no price for (the backup's, or
 * a custom one); [Prices.known] is false when he left them out, and the cost estimate then says it is incomplete.
 */
data class JobModelChoice(
    val source: ModelSource = ModelSource.DEFAULT,
    val customModel: String = "",
    val prices: Prices = Prices(0.0, 0.0, 0.0),
)

/** The model a request should use, and whether it must go to the backup provider's client. */
data class ResolvedModel(val model: String, val useBackup: Boolean, val prices: Prices?)

object JobModels {
    /**
     * Turns a choice into a model. Anything that cannot be honoured falls back to the main deep model, which is what
     * these jobs used before: a backup choice with no backup set, or a custom choice with a blank name.
     */
    fun resolve(choice: JobModelChoice, ai: AiConfig, backup: BackupProvider?): ResolvedModel {
        val deep = ResolvedModel(ai.deepModel, useBackup = false, prices = null)
        return when (choice.source) {
            ModelSource.DEFAULT, ModelSource.MAIN_DEEP -> deep
            ModelSource.MAIN_FAST -> ResolvedModel(ai.chatModel, useBackup = false, prices = null)
            ModelSource.BACKUP_DEEP ->
                if (backup == null) deep else ResolvedModel(backup.deepModel, useBackup = true, prices = choice.prices.takeIf { it.known })
            ModelSource.CUSTOM ->
                choice.customModel.trim().takeIf { it.isNotEmpty() }
                    ?.let { ResolvedModel(it, useBackup = false, prices = choice.prices.takeIf { p -> p.known }) } ?: deep
        }
    }

    /**
     * The strongest model he has available, for the "Use the strongest available" button: the backup provider's deep
     * model when it is Anthropic's (the strongest the app can reach), otherwise the main deep model. A suggestion
     * only; it is never applied without his tap, and the scoreboard should confirm it helps first.
     */
    fun strongest(backup: BackupProvider?): ModelSource =
        if (backup != null && backup.anthropic) ModelSource.BACKUP_DEEP else ModelSource.MAIN_DEEP
}
