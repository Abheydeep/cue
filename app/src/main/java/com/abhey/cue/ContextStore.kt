package com.abhey.cue

import android.content.Context

/**
 * Persists Hinge profile data and recent conversation per person key.
 * Profile context survives app restarts and is fed to the AI for personalised replies.
 * mergeProfile() accumulates prompts across multiple scroll events instead of overwriting.
 */
object ContextStore {

    private const val PREFS = "cue_app_context"

    // ── Profile ───────────────────────────────────────────────────────────────

    /** Overwrite the whole profile string. Use mergeProfile() for incremental scraping. */
    fun saveProfile(context: Context, key: String, profile: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("profile_$key", profile).apply()
    }

    fun getProfile(context: Context, key: String): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("profile_$key", "") ?: ""
    }

    /**
     * Merge a newly-scraped profile fragment into the stored one.
     * - Always keeps the latest name/age/job (non-blank wins).
     * - Accumulates prompts: adds any prompt question we haven't seen yet.
     * - Stores prompt count separately for quick completeness checks.
     */
    fun mergeProfile(context: Context, key: String, newName: String, newAge: String, newJob: String, newPrompts: List<Pair<String, String>>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString("profile_$key", "") ?: ""

        // Parse existing stored prompts (stored as "Q: ...\n  A: ..." blocks)
        val existingPromptMap = mutableMapOf<String, String>()
        var existingName = ""
        var existingAge = ""
        var existingJob = ""

        existing.lines().forEach { line ->
            when {
                line.startsWith("Name:") -> {
                    val parts = line.removePrefix("Name:").trim().split(",").map { it.trim() }
                    existingName = parts.getOrElse(0) { "" }
                    if (parts.size > 1) existingAge = parts[1]
                }
                line.startsWith("Job:") -> existingJob = line.removePrefix("Job:").trim()
                line.trimStart().startsWith("Q:") -> {
                    val q = line.trimStart().removePrefix("Q:").trim()
                    existingPromptMap[q] = "" // placeholder until we see the A
                }
                line.trimStart().startsWith("A:") -> {
                    // Assign answer to the last Q key
                    val a = line.trimStart().removePrefix("A:").trim()
                    val lastKey = existingPromptMap.keys.lastOrNull()
                    if (lastKey != null && existingPromptMap[lastKey].isNullOrBlank()) {
                        existingPromptMap[lastKey] = a
                    }
                }
            }
        }

        // Merge: new values win when non-blank
        val mergedName = if (newName.isNotBlank()) newName else existingName
        val mergedAge  = if (newAge.isNotBlank()) newAge  else existingAge
        val mergedJob  = if (newJob.isNotBlank()) newJob  else existingJob

        // Add new prompts that aren't already stored
        newPrompts.forEach { (q, a) ->
            if (q.isNotBlank() && !existingPromptMap.containsKey(q)) {
                existingPromptMap[q] = a
            }
        }

        // Rebuild profile string
        val merged = buildString {
            if (mergedName.isNotBlank()) appendLine("Name: $mergedName${if (mergedAge.isNotBlank()) ", $mergedAge" else ""}")
            if (mergedJob.isNotBlank()) appendLine("Job: $mergedJob")
            if (existingPromptMap.isNotEmpty()) {
                appendLine("Prompts:")
                existingPromptMap.entries.take(5).forEach { (q, a) ->
                    appendLine("  Q: $q")
                    appendLine("  A: $a")
                }
            }
        }.trim()

        prefs.edit()
            .putString("profile_$key", merged)
            .putInt("prompt_count_$key", existingPromptMap.size)
            .apply()

        Logger.log("ContextStore", "Merged profile[$key]: name=$mergedName prompts=${existingPromptMap.size}")
    }

    /** How many distinct prompts have been accumulated for this person. */
    fun promptCount(context: Context, key: String): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("prompt_count_$key", 0)
    }

    // ── Conversation ──────────────────────────────────────────────────────────

    fun saveConversation(context: Context, key: String, conversation: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("conv_$key", conversation).apply()
    }

    fun getConversation(context: Context, key: String): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("conv_$key", "") ?: ""
    }

    // ── Clear ─────────────────────────────────────────────────────────────────

    fun clear(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("profile_$key")
            .remove("conv_$key")
            .remove("prompt_count_$key")
            .apply()
    }
}
