package com.umair.purpose.cost

/** This month's token totals for one model (Room fills this from a GROUP BY). */
data class ModelUsage(
    val model: String,
    val requests: Int,
    val cacheHitTokens: Long,
    val cacheMissTokens: Long,
    val completionTokens: Long,
    /** Sent in the provider's off-peak hours (UPDATE-17). */
    val offPeak: Boolean = false,
)

/** UPDATE-17: usage per feature (purpose and tier), model and price window. */
data class FeatureUsage(
    val purpose: String,
    val tier: String?,
    val model: String,
    val offPeak: Boolean,
    val requests: Int,
    val promptTokens: Long,
    val cacheHitTokens: Long,
    val cacheMissTokens: Long,
    val completionTokens: Long,
)

/** USD per 1M tokens. */
data class Prices(val cacheHit: Double, val cacheMiss: Double, val output: Double) {
    val known: Boolean get() = cacheMiss > 0.0 || output > 0.0
}

/** Which prices apply: the chat (Flash) and deep (Pro) models, and the off-peak share (0.5 = half price). */
data class Pricing(
    val chatModel: String,
    val chat: Prices,
    val deepModel: String,
    val deep: Prices,
    val offPeakFactor: Double = 1.0,
    /** Phase 5: prices he entered for other models (the backup's, or a custom one) used by a slow job. */
    val extra: Map<String, Prices> = emptyMap(),
) {
    fun forModel(model: String): Prices? = when (model) {
        chatModel -> chat
        deepModel -> deep
        else -> extra[model]
    }
}

data class CostEstimate(val usd: Double, val complete: Boolean)

/** The features Settings shows costs for (UPDATE-17). */
enum class Feature(val label: String) {
    CHAT_FAST("Chat, fast"),
    CHAT_DEEP("Chat, deep"),
    REFLECTION("Reflection"),
    LETTERS("Letters"),
    UPKEEP("Snapshot, chapters, gardening"),
    GROWTH("Growth tree and journeys"),
    TEST_BENCH("Test bench"),
    OTHER("Other");

    companion object {
        fun of(purpose: String, tier: String?): Feature = when (purpose) {
            "chat" -> if (tier == "deep") CHAT_DEEP else CHAT_FAST
            "reflection" -> REFLECTION
            "letter" -> LETTERS
            "snapshot", "chapter", "gardening" -> UPKEEP
            "milestone", "journey", "journey_adapt" -> GROWTH
            "testbench", "compare" -> TEST_BENCH
            else -> OTHER
        }
    }
}

data class FeatureCost(val feature: Feature, val requests: Int, val usd: Double)

data class Breakdown(
    /** Most expensive first; features with no use are left out. */
    val features: List<FeatureCost>,
    val totalUsd: Double,
    /** Share of input tokens served from the provider's cache, or null with no input yet. */
    val cacheHitRate: Double?,
    /** Share of chat messages that went to the deep model, or null with no chat yet. */
    val deepShare: Double?,
    /** False if some usage had no known price (it counts as 0). */
    val complete: Boolean,
)

object CostEstimator {
    /** USD for one block of usage, or null when its model has no known price. */
    fun cost(model: String, hit: Long, miss: Long, out: Long, offPeak: Boolean, pricing: Pricing): Double? {
        val p = pricing.forModel(model)?.takeIf { it.known } ?: return null
        val factor = if (offPeak) pricing.offPeakFactor else 1.0
        return (hit * p.cacheHit + miss * p.cacheMiss + out * p.output) / 1_000_000.0 * factor
    }

    /**
     * Prices usage by matching each model to the chat or deep model's prices.
     * [CostEstimate.complete] is false if some usage had no known price (it counts as 0).
     */
    fun estimate(
        usage: List<ModelUsage>, chatModel: String, chat: Prices, deepModel: String, deep: Prices, offPeakFactor: Double = 1.0,
        /** Phase 5: prices he entered for the backup's or a custom model, so their usage is not counted as free. */
        extra: Map<String, Prices> = emptyMap(),
    ): CostEstimate {
        val pricing = Pricing(chatModel, chat, deepModel, deep, offPeakFactor, extra)
        var usd = 0.0
        var complete = true
        for (u in usage) {
            val c = cost(u.model, u.cacheHitTokens, u.cacheMissTokens, u.completionTokens, u.offPeak, pricing)
            if (c == null) complete = false else usd += c
        }
        return CostEstimate(usd, complete)
    }

    fun breakdown(usage: List<FeatureUsage>, pricing: Pricing): Breakdown {
        val byFeature = LinkedHashMap<Feature, Pair<Int, Double>>()
        var complete = true
        for (u in usage) {
            val c = cost(u.model, u.cacheHitTokens, u.cacheMissTokens, u.completionTokens, u.offPeak, pricing)
            if (c == null) complete = false
            val f = Feature.of(u.purpose, u.tier)
            val (n, usd) = byFeature[f] ?: (0 to 0.0)
            byFeature[f] = (n + u.requests) to (usd + (c ?: 0.0))
        }
        val input = usage.sumOf { it.cacheHitTokens + it.cacheMissTokens }
        val chat = usage.filter { it.purpose == "chat" }
        val chatRequests = chat.sumOf { it.requests }
        return Breakdown(
            features = byFeature.map { (f, v) -> FeatureCost(f, v.first, v.second) }.sortedByDescending { it.usd },
            totalUsd = byFeature.values.sumOf { it.second },
            cacheHitRate = if (input > 0) usage.sumOf { it.cacheHitTokens }.toDouble() / input else null,
            deepShare = if (chatRequests > 0) chat.filter { it.tier == "deep" }.sumOf { it.requests }.toDouble() / chatRequests else null,
            complete = complete,
        )
    }
}
