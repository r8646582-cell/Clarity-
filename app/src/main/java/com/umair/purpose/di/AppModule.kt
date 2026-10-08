package com.umair.purpose.di

import android.content.Context
import com.umair.purpose.ai.AiClient
import com.umair.purpose.ai.OpenAiCompatibleClient
import com.umair.purpose.chat.ActiveConversation
import com.umair.purpose.chat.ChatPrefixCache
import com.umair.purpose.chat.ReplyEngine
import com.umair.purpose.data.db.PurposeDatabase
import com.umair.purpose.data.repo.ChatRepository
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.security.SecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context, secrets: SecretStore,
        prefs: com.umair.purpose.data.repo.UiPrefs, receipts: com.umair.purpose.chat.ActionReceiptStore,
        offRecord: com.umair.purpose.data.repo.OffRecord): PurposeDatabase =
        PurposeDatabase.open(context) { secrets.dbPassphrase() }.also { db ->
            db.datasetWork.onReplaced { prefs.datasetReplaced(); receipts.clear(); offRecord.end() }
        }

    @Provides
    @Singleton
    fun activeConversation(chat: ChatRepository, engine: ReplyEngine, prefix: ChatPrefixCache, db: PurposeDatabase): ActiveConversation =
        ActiveConversation(chat, cancelReply = engine::cancel, clearPrefix = prefix::clear).also { active ->
            db.datasetWork.onReplaced { active.stopViewing() }
        }

    /** Phase 2: the on-device embedding model. Without its asset files it returns no vectors and search stays full-text. */
    @Provides
    @Singleton
    fun embedder(@ApplicationContext context: Context): com.umair.purpose.memory.Embedder =
        com.umair.purpose.memory.OnnxEmbedder(context)

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Large prompts (letters, chapters) need more than OkHttp's 10s default to upload on slow uplinks.
        .writeTimeout(60, TimeUnit.SECONDS)
        // Read timeouts are set per kind of call in OpenAiCompatibleClient (120s streaming gap, 300s one-shot).
        .build()

    @Provides
    @Singleton
    fun failoverPolicy(): com.umair.purpose.ai.FailoverPolicy = com.umair.purpose.ai.FailoverPolicy()

    /**
     * The main provider, with the backup provider behind it (UPDATE-16). Config and keys are read per request, so
     * Settings changes apply at once.
     */
    @Provides
    @Singleton
    fun aiClient(
        http: OkHttpClient,
        settings: SettingsRepository,
        secrets: SecretStore,
        policy: com.umair.purpose.ai.FailoverPolicy,
        connectivity: com.umair.purpose.system.Connectivity,
    ): AiClient {
        val main = OpenAiCompatibleClient(http = http, config = { settings.get().ai }, apiKey = { secrets.apiKey() })
        var cached: Pair<com.umair.purpose.data.repo.BackupProvider, AiClient>? = null
        return com.umair.purpose.ai.FailoverAiClient(
            main = main,
            backup = backup@{
                val s = settings.get()
                val b = s.backupProvider?.takeIf { !secrets.backupApiKey().isNullOrBlank() } ?: return@backup null
                val client = cached?.takeIf { it.first == b }?.second ?: (
                    if (b.anthropic) com.umair.purpose.ai.AnthropicClient(http, baseUrl = { b.baseUrl }, apiKey = { secrets.backupApiKey() })
                    else OpenAiCompatibleClient(
                        http = http,
                        config = { s.ai.copy(provider = "Backup", baseUrl = b.baseUrl, chatModel = b.chatModel, deepModel = b.deepModel, supportsThinkingToggle = false) },
                        apiKey = { secrets.backupApiKey() },
                    )
                ).also { cached = b to it }
                client to { r: com.umair.purpose.ai.AiRequest -> com.umair.purpose.ai.FailoverAiClient.mapRequest(r, s.ai.deepModel, b.chatModel, b.deepModel) }
            },
            policy = policy,
            online = connectivity::isOnline,
        )
    }
}
