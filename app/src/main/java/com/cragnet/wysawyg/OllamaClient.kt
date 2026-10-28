package com.cragnet.wysawyg

import android.content.Context
import android.net.Uri
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OllamaClient(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun configuration(): CloudConfiguration {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString(MainActivity.PREF_ALARMA_URL, "https://ollama.com/api/chat")!!
        val apiKey = prefs.getString(MainActivity.PREF_API_KEY, "") ?: ""
        require(apiKey.all { it in ' '..'~' }) { "The provider API key contains unsupported characters." }
        val model = prefs.getString(MainActivity.PREF_MODEL, "gemma4:e4b")!!
        val systemPrompt = prefs.getString(MainActivity.PREF_SYSTEM_PROMPT, "Transcribe the audio exactly. Output only the spoken words, no commentary.")!!
        return CloudConfiguration(CloudDestination.parse(url, BuildConfig.DEBUG), apiKey, model, systemPrompt)
    }

    suspend fun transcribe(wavBytes: ByteArray, approved: CloudConfiguration = configuration()): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        check(PrivacyConsent.hasCloud(context, approved.destination)) { context.getString(R.string.cloud_setup_needed) }
        check(TranscriptionMode.fromStored(prefs.getString(TranscriptionMode.KEY, null)) == TranscriptionMode.CLOUD &&
            configuration() == approved) { "Cloud settings changed. Record again using the current settings." }
        val url = approved.destination.url
        val apiKey = approved.apiKey
        val model = approved.model
        val systemPrompt = approved.prompt

        val uri = Uri.parse(url)
        val modelFromUrl = uri.getQueryParameter("model")
        val effectiveModel = modelFromUrl ?: model

        // Ollama Cloud / public endpoints work best with native /api/chat for multimodal audio.
        // OpenAI-compatible /v1/chat/completions does not reliably pass audio on Ollama Cloud.
        val cleanUri = uri.buildUpon().clearQuery().fragment(null).build().toString().trimEnd('/')
        val isOllama = uri.host == "ollama.com" || cleanUri.endsWith("/api/chat") || uri.port == 11434
        val endpoint = if (isOllama) {
            cleanUri.removeSuffix("/api/chat") + "/api/chat"
        } else {
            cleanUri.removeSuffix("/v1/audio/transcriptions").removeSuffix("/v1/chat/completions").removeSuffix("/v1") + "/v1/chat/completions"
        }

        transcribeAudio(endpoint, effectiveModel, systemPrompt, wavBytes, apiKey, isOllama)
    }

    private fun transcribeAudio(endpoint: String, model: String, systemPrompt: String, wavBytes: ByteArray, apiKey: String, nativeOllama: Boolean): String {
        if (!nativeOllama) return transcribeCompatible(endpoint, model, wavBytes, apiKey)

        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "Transcribe this audio.")
                put("images", JSONArray().apply { put(base64Audio) })
            })
        }

        val json = JSONObject().apply {
            put("model", model)
            put("stream", false)
            put("messages", messages)
            put("options", JSONObject().apply {
                put("temperature", 0.1)
                put("num_predict", 256)
            })
        }

        json.put("think", false)

        val body = json.toString().toRequestBody("application/json".toMediaType())
        WysawygLogger.d("Transcription request body length: ${body.contentLength()} bytes")

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(body)

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        val request = requestBuilder.build()
        val responseBody = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Transcription error ${response.code}" }
            response.body?.string() ?: error("Empty response")
        }

        return responseText(responseBody) { it.getJSONObject("message").optString("content", "") }
    }
    private fun transcribeCompatible(endpoint: String, model: String, wavBytes: ByteArray, apiKey: String): String {
        val transcriptionEndpoint = endpoint.removeSuffix("/chat/completions") + "/audio/transcriptions"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", "recording.wav", wavBytes.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder().url(transcriptionEndpoint).post(body).apply {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }.build()
        return client.newCall(request).execute().use { response ->
            val result = response.body?.string() ?: error("Empty transcription response")
            check(response.isSuccessful) { "Transcription error ${response.code}" }
            responseText(result) { it.getString("text") }
        }
    }

    private fun responseText(body: String, read: (JSONObject) -> String): String = try {
        read(JSONObject(body)).trim()
    } catch (_: JSONException) {
        error("Invalid transcription response")
    }
}
