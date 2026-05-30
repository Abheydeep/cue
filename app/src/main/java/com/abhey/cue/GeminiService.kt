package com.abhey.cue

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object GeminiService {

    private var apiKey = ""
    private const val MODEL = "meta/llama-4-maverick-17b-128e-instruct"
    private const val URL = "https://integrate.api.nvidia.com/v1/chat/completions"

    fun setApiKey(key: String) {
        apiKey = key
        Logger.log("AI", "API key set (${key.length} chars) prefix=${key.take(8)}")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun getSuggestions(
        conversation: String,
        appName: String,
        profileContext: String = ""
    ): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Logger.log("AI", "ERROR: API key is blank — aborting")
            return@withContext emptyList()
        }

        Logger.log("AI", "Calling NVIDIA/$MODEL for $appName | conv ${conversation.length} chars")

        val systemPrompt = buildSystemPrompt(appName, profileContext)
        val userMsg = "Conversation so far:\n$conversation"

        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userMsg) })
            })
            put("max_tokens", 300)
            put("temperature", 0.9)
            put("stream", false)
        }.toString()

        val request = Request.Builder()
            .url(URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val httpCode = response.code
            val body = response.body?.string() ?: ""
            Logger.log("AI", "HTTP $httpCode | body ${body.length} chars")
            if (!response.isSuccessful) {
                Logger.log("AI", "ERROR: ${body.take(200)}")
                return@withContext emptyList()
            }
            val replies = parseReplies(body)
            Logger.log("AI", "Got ${replies.size} replies")
            replies
        } catch (e: Exception) {
            Logger.log("AI", "EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
    }

    private fun buildSystemPrompt(appName: String, profileContext: String): String {
        val profileSection = if (profileContext.isNotBlank())
            "Their profile info (use this to personalise replies naturally):\n$profileContext\n\n"
        else ""

        return """You are a sharp conversational assistant helping craft replies for $appName.

${profileSection}Give exactly 3 reply options. Each should feel human, not AI-generated.
- Option 1: Direct and confident
- Option 2: Light and engaging
- Option 3: Curious / asks something back

Rules:
- Keep each reply under 2 sentences
- No emojis unless it fits naturally
- Match the tone and language of the conversation (Hindi/English/Hinglish)
- If context mentions specific interests or background, reference them naturally

Format your response EXACTLY like this (no extra text):
REPLY1: <reply here>
REPLY2: <reply here>
REPLY3: <reply here>"""
    }

    private fun parseReplies(responseJson: String): List<String> {
        return try {
            val text = JSONObject(responseJson)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val replies = mutableListOf<String>()
            text.lines().forEach { line ->
                when {
                    line.startsWith("REPLY1:") -> replies.add(line.removePrefix("REPLY1:").trim())
                    line.startsWith("REPLY2:") -> replies.add(line.removePrefix("REPLY2:").trim())
                    line.startsWith("REPLY3:") -> replies.add(line.removePrefix("REPLY3:").trim())
                }
            }
            replies
        } catch (e: Exception) {
            Logger.log("AI", "Parse error: ${e.message}")
            emptyList()
        }
    }
}
