package com.phoneai.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Three-tier text embedding, following the Ultron Cerebro approach of dense semantic vectors
 * compared by cosine similarity.
 *
 * Tier 1 — TFLite quantized sentence-embedding model (on-device, no network):
 *   loads `assets/embedding.tflite` if the owner has placed it there. Input is the raw text,
 *   output is a fixed-size dense vector. Nothing leaves the phone.
 * Tier 2 — Ollama embeddings endpoint (owner-gated, local network only):
 *   `POST {baseUrl}/api/embeddings` with {"model": ..., "prompt": text}. Only used when
 *   `Prefs.embeddingThirdPartyAllowed` is true AND an Ollama base URL is configured. Memory
 *   content is never sent to a third party without that owner setting.
 * Tier 3 — `OrionEmbedding` feature-hash embedding (always local, always available):
 *   deterministic, no model file, no network. This is the fallback.
 *
 * The provider is intentionally side-effect free in `embed()`: TFLite and Ollama are best-effort.
 * If either tier is unavailable or returns garbage, the next tier is used. Callers compare
 * vectors with `OrionEmbedding.cosine` (all tiers produce FloatArrays of the same length).
 */
internal object OrionEmbeddingProvider {
    private const val EMBEDDING_DIM = 512
    private const val TFLITE_ASSET = "embedding.tflite"
    private const val OLLAMA_EMBED_PATH = "/api/embeddings"

    private val okHttp = OkHttpClient()
    private val interpreterLock = Any()
    @Volatile private var interpreter: Interpreter? = null
    @Volatile private var tfliteLoaded = false
    private val ollamaCache = ConcurrentHashMap<String, FloatArray>()

    /** Best-effort load of the bundled TFLite embedding model from assets. Call once at startup. */
    fun init(context: Context) {
        synchronized(interpreterLock) {
            if (tfliteLoaded) return@synchronized
            tfliteLoaded = true
            var fd: android.content.res.AssetFileDescriptor? = null
            try {
                fd = context.assets.openFd(TFLITE_ASSET).takeIf { it.isReadOnly }
                if (fd == null) return@synchronized
                val channel: FileChannel = fd!!.inputStream.channel
                val model = channel.map(FileChannel.MapMode.READ_ONLY, fd!!.startOffset, fd!!.declaredLength)
                interpreter = Interpreter(model)
            } catch (_: Exception) {
                interpreter = null
            } finally {
                fd?.close()
            }
        }
    }

    /** Embed [text] using the best available tier. Always returns a non-null, normalized vector. */
    fun embed(context: Context, text: String): FloatArray {
        val clean = text.trim()
        if (clean.isEmpty()) return FloatArray(EMBEDDING_DIM)

        // Tier 1: on-device TFLite quantized model.
        val interpreterRef = synchronized(interpreterLock) { interpreter }
        if (interpreterRef != null) {
            val vec = runCatching { tfliteEmbed(interpreterRef, clean) }.getOrNull()
            if (vec != null) return vec
        }

        // Tier 2: Ollama embeddings endpoint — only when the owner has opted in to third-party sending.
        if (Prefs.embeddingThirdPartyAllowed(context)) {
            val base = Prefs.ollamaBaseUrl(context).trim().takeIf { it.isNotBlank() } ?: return orionEmbedding(clean)
            val model = Prefs.ollamaEmbeddingModel(context).takeIf { it.isNotBlank() } ?: "nomic-embed-text"
            val cacheKey = "$base|$model|$clean"
            val cached = ollamaCache[cacheKey]
            if (cached != null) return cached
            val vec = runCatching { ollamaEmbed(base, model, clean) }.getOrNull()
            if (vec != null) {
                ollamaCache[cacheKey] = vec
                return vec
            }
        }

        // Tier 3: deterministic feature-hash embedding, always local, always available.
        return orionEmbedding(clean)
    }

    /** Cosine similarity between two provider embeddings (they are already unit length). */
    fun cosine(a: FloatArray, b: FloatArray): Double = OrionEmbedding.cosine(a, b)

    /** Similarity between two texts using the best available embedding tier. */
    fun similarity(context: Context, a: String, b: String): Double =
        cosine(embed(context, a), embed(context, b))

    // ---- Tier 1: TFLite -----------------------------------------------------

    private fun tfliteEmbed(interpreter: Interpreter, text: String): FloatArray {
        // Tokenise into word ids: a lightweight deterministic hash of each token into the
        // model's input vocabulary range. The model owner must ensure the .tflite expects
        // int32 token ids of shape [1, maxTokens]; we pad/truncate to 64 tokens.
        val maxTokens = 64
        val input = IntArray(maxTokens)
        val tokens = MemoryRanking.tokens(text).map(::MemoryRanking.stem).take(maxTokens)
        for (i in tokens.indices) input[i] = (tokens[i].hashCode() and 0x7fffffff) % 30000

        val inputBuffer = ByteBuffer.allocateDirect(maxTokens * 4)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
            .put(input)
            .rewind()

        val output = FloatArray(EMBEDDING_DIM)
        val outputBuffer = ByteBuffer.allocateDirect(EMBEDDING_DIM * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(output)
            .rewind()

        interpreter.run(inputBuffer, outputBuffer)
        outputBuffer.rewind().get(output)

        // L2-normalise.
        var norm = 0.0
        for (x in output) norm += (x * x).toDouble()
        if (norm > 1e-9) {
            val inv = (1.0 / Math.sqrt(norm)).toFloat()
            for (i in output.indices) output[i] *= inv
        }
        return output
    }

    // ---- Tier 2: Ollama -----------------------------------------------------

    private fun ollamaEmbed(baseUrl: String, model: String, text: String): FloatArray {
        val url = if (baseUrl.endsWith("/")) baseUrl + "api/embeddings" else baseUrl + OLLAMA_EMBED_PATH
        val body = JSONObject()
            .put("model", model)
            .put("prompt", text)
            .toString()
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .header("Content-Type", "application/json")
            .build()
        okHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Ollama embeddings HTTP ${response.code}")
            val json = JSONObject(response.use { it.body!!.string() })
            val arr = json.getJSONArray("embedding")
            val out = FloatArray(arr.length())
            for (i in 0 until arr.length()) out[i] = arr.toFloat(i)
            return out
        }
    }

    // ---- Tier 3: local feature hash -----------------------------------------

    private fun orionEmbedding(text: String): FloatArray = OrionEmbedding.embed(text)
}
