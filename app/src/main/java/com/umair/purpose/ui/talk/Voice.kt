package com.umair.purpose.ui.talk

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.umair.purpose.data.repo.VoiceLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** One installed TextToSpeech voice, for the picker in Settings. */
data class VoiceOption(val name: String, val label: String, val online: Boolean, val quality: Int)

/**
 * Reads coach replies aloud with the phone's TextToSpeech. Voice, speed and pitch come from Settings
 * (CLAUDE.md "Read aloud: voices"); they're kept in [com.umair.purpose.data.repo.UiPrefs].
 */
@Singleton
class Speaker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: com.umair.purpose.data.repo.UiPrefs,
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initDone = false
    private val waiting = mutableListOf<(Boolean) -> Unit>()

    /** Runs [block] once the engine is up, with whether it works. A failed engine is tried again next time. */
    @Synchronized
    private fun whenReady(block: (Boolean) -> Unit) {
        if (initDone && ready) return block(true)
        if (initDone && !ready) {
            tts?.shutdown()
            tts = null
            initDone = false
        }
        waiting += block
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                val run = synchronized(this) {
                    ready = status == TextToSpeech.SUCCESS
                    initDone = true
                    waiting.toList().also { waiting.clear() }
                }
                run.forEach { it(ready) }
            }
        }
    }

    fun speak(text: String, language: VoiceLanguage) = whenReady { ok -> if (ok) say(text, language) }

    fun stop() {
        tts?.stop()
    }

    /**
     * Voices for [language], best first: higher quality, then network voices (they usually sound more natural;
     * they send the text to the voice provider, e.g. Google, to be spoken).
     */
    suspend fun voices(language: VoiceLanguage): List<VoiceOption> = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        whenReady { ok ->
            if (!ok) {
                if (cont.isActive) cont.resumeWith(Result.success(emptyList()))
                return@whenReady
            }
            val want = (language.tag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault()).language
            val list = runCatching { tts?.voices.orEmpty() }.getOrDefault(emptySet())
                .filter { it.locale.language == want && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenByDescending { it.isNetworkConnectionRequired }.thenBy { it.name })
                .map { v ->
                    VoiceOption(
                        name = v.name,
                        label = voiceLabel(v),
                        online = v.isNetworkConnectionRequired,
                        quality = v.quality,
                    )
                }
            if (cont.isActive) cont.resumeWith(Result.success(list))
        }
    }

    private fun voiceLabel(v: android.speech.tts.Voice): String {
        val country = v.locale.displayCountry.takeIf { it.isNotBlank() }
        val quality = when {
            v.quality >= android.speech.tts.Voice.QUALITY_VERY_HIGH -> "very natural"
            v.quality >= android.speech.tts.Voice.QUALITY_HIGH -> "natural"
            else -> "basic"
        }
        // Engine names look like "en-us-x-iom-local": keep the distinctive middle part.
        val id = v.name.split('-').filter { it.length in 2..4 && it != v.locale.language && it != "x" && it.lowercase() != v.locale.country.lowercase() }
            .firstOrNull { it != "local" && it != "network" }?.uppercase()
        return listOfNotNull(country, id, quality).joinToString(" · ")
    }

    private fun say(text: String, language: VoiceLanguage) {
        val engine = tts ?: return
        engine.setLanguage(language.tag?.let { Locale.forLanguageTag(it) } ?: Locale.getDefault())
        prefs.voiceName?.let { name -> runCatching { engine.voices?.firstOrNull { it.name == name } }.getOrNull()?.let { engine.voice = it } }
        engine.setSpeechRate(prefs.voiceSpeed)
        engine.setPitch(prefs.voicePitch)
        // Long replies go in chunks: engines cap a single utterance.
        val chunks = text.split(Regex("""\n\s*\n""")).flatMap { it.chunked(TextToSpeech.getMaxSpeechInputLength() - 1) }
        chunks.forEachIndexed { i, chunk ->
            engine.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "purpose-$i")
        }
    }

    /** "Get better voices": the phone's text-to-speech settings (Google's speech services offer more voices). */
    fun openSystemSettings() {
        val tries = listOf(
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            val ok = runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return
        }
    }
}

/**
 * Voice typing with Android's SpeechRecognizer. The words go into the input box; nothing is sent by itself.
 * Uses the phone's speech service.
 */
class VoiceTyping(private val context: Context, private val onText: (String) -> Unit, private val onState: (Boolean) -> Unit) {
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(language: VoiceLanguage) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onText)
                onState(false)
            }
            override fun onError(error: Int) = onState(false)
            override fun onEndOfSpeech() = Unit
            override fun onReadyForSpeech(params: Bundle?) = onState(true)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            language.tag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }
}
