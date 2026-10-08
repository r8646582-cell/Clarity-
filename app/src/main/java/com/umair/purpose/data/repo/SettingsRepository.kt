package com.umair.purpose.data.repo

import androidx.room.withTransaction
import com.umair.purpose.ai.AiConfig
import com.umair.purpose.ai.AiDefaults
import com.umair.purpose.chat.ToughLove
import com.umair.purpose.cost.Prices
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.db.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeChoice(val wire: String, val label: String) {
    NIGHT("night", "Night"), DAY("day", "Day"), SYSTEM("system", "Follow system");

    companion object {
        fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: NIGHT
    }
}

enum class VoiceLanguage(val wire: String, val label: String, val tag: String?) {
    DEFAULT("default", "Phone default", null), ENGLISH("en", "English", "en-US"), URDU("ur", "Urdu", "ur-PK");

    companion object {
        fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: DEFAULT
    }
}

/** UPDATE-16: the backup AI provider, used after the main one fails 3 times in a row. The key is in SecretStore. */
data class BackupProvider(
    /** "openai" (any OpenAI-compatible API) or "anthropic". */
    val kind: String,
    val baseUrl: String,
    val chatModel: String,
    val deepModel: String,
) {
    val anthropic: Boolean get() = kind == ANTHROPIC

    companion object {
        const val OPENAI = "openai"
        const val ANTHROPIC = "anthropic"
    }
}

data class AppSettings(
    val toughLove: ToughLove,
    val ai: AiConfig,
    /** USD per 1M tokens for the chat model and the reflection/letter model. */
    val chatPrices: Prices = AiDefaults.CHAT_PRICES,
    val deepPrices: Prices = AiDefaults.DEEP_PRICES,
    val theme: ThemeChoice = ThemeChoice.NIGHT,
    val lockEnabled: Boolean = true,
    val hideInRecents: Boolean = true,
    val voiceLanguage: VoiceLanguage = VoiceLanguage.DEFAULT,
    val pulseEnabled: Boolean = false,
    val readAloud: Boolean = false,
    val alwaysDeep: Boolean = false,
    val monthlyBudget: Double = 5.0,
    val autoBackup: Boolean = false,
    val backupFolder: String? = null,
    /** Null when no backup provider is set. */
    val backupProvider: BackupProvider? = null,
    val offPeak: com.umair.purpose.cost.OffPeakConfig = com.umair.purpose.cost.OffPeakConfig(),
    val lastRestoreTestAt: Long? = null,
    val keystoreConfirmedAt: Long? = null,
    val providerKeyExpiry: String? = null,
) {
    val pricing: com.umair.purpose.cost.Pricing
        get() = com.umair.purpose.cost.Pricing(ai.chatModel, chatPrices, ai.deepModel, deepPrices, if (offPeak.enabled) offPeak.factor else 1.0)
}

@Singleton
class SettingsRepository @Inject constructor(private val db: PurposeDatabase) {
    private val dao = db.settingsDao()

    fun observe(): Flow<AppSettings> = dao.observe().map { it.toModel() }

    suspend fun get(): AppSettings = dao.get().toModel()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        db.withTransaction { dao.upsert(transform(get()).toEntity()) }
    }

    private fun Settings?.toModel(): AppSettings {
        if (this == null) return AppSettings(ToughLove.BALANCED, AiDefaults.DEEPSEEK)
        return AppSettings(
            toughLove = ToughLove.fromWire(toughLove),
            ai = AiConfig(
                provider = provider,
                baseUrl = baseUrl,
                chatModel = chatModel,
                deepModel = deepModel,
                chatTemperature = chatTemperature,
                reflectionTemperature = reflectionTemperature,
                supportsThinkingToggle = supportsThinkingToggle,
            ),
            chatPrices = Prices(chatPriceCacheHit, chatPriceCacheMiss, chatPriceOutput),
            deepPrices = Prices(deepPriceCacheHit, deepPriceCacheMiss, deepPriceOutput),
            theme = ThemeChoice.fromWire(theme),
            lockEnabled = lockEnabled,
            hideInRecents = hideInRecents,
            voiceLanguage = VoiceLanguage.fromWire(voiceLanguage),
            pulseEnabled = pulseEnabled,
            readAloud = readAloud,
            alwaysDeep = alwaysDeep,
            monthlyBudget = monthlyBudget,
            autoBackup = autoBackup,
            backupFolder = backupFolder,
            backupProvider = backupProvider.takeIf { it.isNotBlank() && backupBaseUrl.isNotBlank() && backupChatModel.isNotBlank() }
                ?.let { BackupProvider(it, backupBaseUrl, backupChatModel, backupDeepModel.ifBlank { backupChatModel }) },
            offPeak = com.umair.purpose.cost.OffPeakConfig(offPeakEnabled, peakWindows, peakWeekdaysOnly, offPeakFactor),
            lastRestoreTestAt = lastRestoreTestAt,
            keystoreConfirmedAt = keystoreConfirmedAt,
            providerKeyExpiry = providerKeyExpiry,
        )
    }

    private fun AppSettings.toEntity() = Settings(
        toughLove = toughLove.wire,
        provider = ai.provider,
        baseUrl = ai.baseUrl,
        chatModel = ai.chatModel,
        deepModel = ai.deepModel,
        chatTemperature = ai.chatTemperature,
        reflectionTemperature = ai.reflectionTemperature,
        supportsThinkingToggle = ai.supportsThinkingToggle,
        chatPriceCacheHit = chatPrices.cacheHit,
        chatPriceCacheMiss = chatPrices.cacheMiss,
        chatPriceOutput = chatPrices.output,
        deepPriceCacheHit = deepPrices.cacheHit,
        deepPriceCacheMiss = deepPrices.cacheMiss,
        deepPriceOutput = deepPrices.output,
        theme = theme.wire,
        lockEnabled = lockEnabled,
        hideInRecents = hideInRecents,
        voiceLanguage = voiceLanguage.wire,
        pulseEnabled = pulseEnabled,
        readAloud = readAloud,
        alwaysDeep = alwaysDeep,
        monthlyBudget = monthlyBudget,
        autoBackup = autoBackup,
        backupFolder = backupFolder,
        backupProvider = backupProvider?.kind.orEmpty(),
        backupBaseUrl = backupProvider?.baseUrl.orEmpty(),
        backupChatModel = backupProvider?.chatModel.orEmpty(),
        backupDeepModel = backupProvider?.deepModel.orEmpty(),
        offPeakEnabled = offPeak.enabled,
        peakWindows = offPeak.peakWindows,
        peakWeekdaysOnly = offPeak.weekdaysOnly,
        offPeakFactor = offPeak.factor,
        lastRestoreTestAt = lastRestoreTestAt,
        keystoreConfirmedAt = keystoreConfirmedAt,
        providerKeyExpiry = providerKeyExpiry,
    )
}
