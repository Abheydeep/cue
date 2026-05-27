package com.abhey.cue

import android.content.Context

/**
 * Persists Hinge profile data and recent conversation per person key.
 * Profile context survives app restarts and is fed to Gemini for personalised replies.
 */
object ContextStore {

    private const val PREFS = "cue_app_context"

    fun saveProfile(context: Context, key: String, profile: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("profile_$key", profile).apply()
    }

    fun getProfile(context: Context, key: String): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("profile_$key", "") ?: ""
    }

    fun saveConversation(context: Context, key: String, conversation: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("conv_$key", conversation).apply()
    }

    fun getConversation(context: Context, key: String): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("conv_$key", "") ?: ""
    }

    fun clear(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove("profile_$key")
            .remove("conv_$key")
            .apply()
    }
}
