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
    private const val MODEL = "gemini-1.5-flash"

    fun setApiKey(key: String) {
        apiKey = key
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun getSuggestions(
        conversation: String,
        appName: String,
        profileContext: String = ""
    ): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext emptyList()

        // URL must be built at call time, not as a const — otherwise the key never gets inserted
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey"
        val prompt = buildPrompt(conversation, appName, profileContext)

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.9)
                put("maxOutputTokens", 500)
            })
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            parseReplies(body)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun buildPrompt(conversation: String, appName: String, profileContext: String): String {
        val profileSection = if (profileContext.isNotBlank()) {
            "Their profile (use this to personalise replies naturally):\n---\n$profileContext\n---\n\n"
        } else ""

        return """
You are a sharp conversational assistant helping craft replies for $appName.

${profileSection}Here is the recent conversation:
---
$conversation
---

Give exactly 3 reply options. Each should feel human, not AI-generated.
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
REPLY3: <reply here>
        """.trimIndent()
    }

    private fun parseReplies(responseJson: String): List<String> {
        return try {
            val json = JSONObject(responseJson)
            val text = json
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

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
            emptyList()
        }
    }
}
