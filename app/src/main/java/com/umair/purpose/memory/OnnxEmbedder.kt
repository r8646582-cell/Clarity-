package com.umair.purpose.memory

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import kotlin.math.sqrt

/**
 * Phase 2: all-MiniLM-L6-v2 (Apache-2.0, 384 dimensions) through ONNX Runtime, entirely on the device. The model
 * files are app assets (`embedding/model.onnx`, `embedding/vocab.txt`); if they are missing or fail to load, every
 * call returns null and retrieval quietly stays full-text only. Nothing here ever leaves the phone.
 */
class OnnxEmbedder(private val context: Context) : Embedder, Closeable {
    override val id = "all-MiniLM-L6-v2-onnx-v1"

    private class Loaded(val env: OrtEnvironment, val session: OrtSession, val tokenizer: WordPieceTokenizer, val wantsTypeIds: Boolean)

    private val loaded: Loaded? by lazy {
        runCatching {
            val model = context.assets.open(MODEL).use { it.readBytes() }
            val vocab = context.assets.open(VOCAB).bufferedReader().use { it.readLines() }
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(model, OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) })
            Loaded(env, session, WordPieceTokenizer(vocab), "token_type_ids" in session.inputNames)
        }.getOrNull()
    }

    val available: Boolean get() = loaded != null

    @Synchronized
    override fun embed(text: String): FloatArray? {
        val m = loaded ?: return null
        if (text.isBlank()) return null
        return runCatching {
            val ids = m.tokenizer.encode(text)
            val n = ids.size
            val inputs = linkedMapOf<String, OnnxTensor>()
            try {
                inputs["input_ids"] = OnnxTensor.createTensor(m.env, arrayOf(ids))
                inputs["attention_mask"] = OnnxTensor.createTensor(m.env, arrayOf(LongArray(n) { 1L }))
                if (m.wantsTypeIds) inputs["token_type_ids"] = OnnxTensor.createTensor(m.env, arrayOf(LongArray(n)))
                m.session.run(inputs).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val tokens = (result[0].value as Array<Array<FloatArray>>)[0]
                    meanPoolAndNormalize(tokens)
                }
            } finally {
                inputs.values.forEach { it.close() }
            }
        }.getOrNull()
    }

    override fun close() {
        loaded?.let { runCatching { it.session.close() } }
    }

    companion object {
        const val MODEL = "embedding/model.onnx"
        const val VOCAB = "embedding/vocab.txt"

        /** Every token has attention (one text, no padding), so pooling is a plain mean, then unit length. */
        fun meanPoolAndNormalize(tokens: Array<FloatArray>): FloatArray {
            val dim = tokens.first().size
            val sum = FloatArray(dim)
            for (t in tokens) for (i in 0 until dim) sum[i] += t[i]
            for (i in 0 until dim) sum[i] /= tokens.size
            var norm = 0.0
            for (v in sum) norm += v * v
            val len = sqrt(norm).toFloat()
            if (len > 0f) for (i in 0 until dim) sum[i] /= len
            return sum
        }
    }
}
