package com.abhey.cue

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChatAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var lastConversation = ""
    private var debounceJob: Job? = null

    // Hinge's real package ID is co.hinge (not com.hinge)
    private val supportedApps = mapOf(
        "com.whatsapp" to "WhatsApp",
        "co.hinge" to "Hinge"
    )

    // Current Hinge match name — used to look up saved profile context
    private var currentHingeKey = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString() ?: return
        val appName = supportedApps[packageName] ?: return

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(800)
            val root = rootInActiveWindow ?: return@launch
            when (appName) {
                "Hinge" -> handleHingeScreen(root)
                "WhatsApp" -> handleWhatsAppScreen(root)
            }
        }
    }

    // ── Hinge ─────────────────────────────────────────────────────────────────

    private fun handleHingeScreen(root: AccessibilityNodeInfo) {
        if (!isHingeChatScreen(root)) {
            // Viewing a profile card — scrape and persist it
            val profile = extractHingeProfile(root)
            if (profile.name.isNotBlank()) {
                currentHingeKey = profile.name
                ContextStore.saveProfile(this, profile.name, profile.toContextString())
            }
            return
        }

        // Inside a chat thread
        val messages = mutableListOf<String>()
        collectTexts(root, messages, minLen = 2, maxLen = 500)
        val conversation = messages.takeLast(10).joinToString("\n")

        if (conversation.isNotBlank() && conversation != lastConversation) {
            lastConversation = conversation
            if (currentHingeKey.isNotBlank()) {
                ContextStore.saveConversation(this, currentHingeKey, conversation)
            }
            val profileContext = ContextStore.getProfile(this, currentHingeKey)
            triggerSuggestions(conversation, "Hinge", profileContext)
        }
    }

    private fun isHingeChatScreen(root: AccessibilityNodeInfo): Boolean {
        val inputNodes = root.findAccessibilityNodeInfosByViewId("co.hinge:id/message_edit_text")
        if (!inputNodes.isNullOrEmpty()) return true
        return findEditText(root) != null
    }

    data class HingeProfile(
        val name: String,
        val age: String,
        val job: String,
        val prompts: List<Pair<String, String>>
    ) {
        fun toContextString(): String = buildString {
            if (name.isNotBlank()) appendLine("Name: $name${if (age.isNotBlank()) ", $age" else ""}")
            if (job.isNotBlank()) appendLine("Job: $job")
            if (prompts.isNotEmpty()) {
                appendLine("Prompts:")
                prompts.forEach { (q, a) -> appendLine("  Q: $q\n  A: $a") }
            }
        }.trim()
    }

    private val hingePromptKeywords = listOf(
        "I'm looking for", "My love language", "A life goal",
        "I go crazy for", "We'll get along", "I'm convinced",
        "My most irrational fear", "Typical Sunday", "I want someone",
        "Two truths and a lie", "I'm weirdly attracted to", "Dating me is like",
        "The one thing I'd like to know", "A social cause I care about",
        "My simple pleasures", "Unusual skills", "I geek out on",
        "The key to my heart", "I recently discovered", "Fact about me",
        "Most spontaneous thing", "I'll fall for you if"
    )

    private val jobKeywords = listOf(
        "Engineer", "Designer", "Doctor", "Student", "Manager",
        "Analyst", "Consultant", "Developer", "Teacher", "Lawyer",
        "Architect", "Artist", "Nurse", "Chef", "Writer"
    )

    private fun extractHingeProfile(root: AccessibilityNodeInfo): HingeProfile {
        val allTexts = mutableListOf<String>()
        collectTexts(root, allTexts)

        var name = ""
        var age = ""
        var job = ""
        val prompts = mutableListOf<Pair<String, String>>()

        var i = 0
        while (i < allTexts.size) {
            val text = allTexts[i]

            // "Name, 27" or "Name"
            if (name.isEmpty() && text.matches(Regex("[A-Z][a-zA-Z ]+(,\\s*\\d{2})?"))) {
                val parts = text.split(",").map { it.trim() }
                name = parts[0]
                if (parts.size > 1 && parts[1].all { it.isDigit() }) age = parts[1]
                i++; continue
            }

            if (age.isEmpty() && text.matches(Regex("\\d{2}"))) {
                age = text; i++; continue
            }

            if (job.isEmpty() && text.length in 3..60
                && jobKeywords.any { text.contains(it, ignoreCase = true) }
            ) {
                job = text; i++; continue
            }

            val matchedPrompt = hingePromptKeywords.firstOrNull { text.contains(it, ignoreCase = true) }
            if (matchedPrompt != null && i + 1 < allTexts.size) {
                val answer = allTexts[i + 1]
                if (answer.length in 2..300) {
                    prompts.add(text to answer)
                    i += 2; continue
                }
            }

            i++
        }

        return HingeProfile(name, age, job, prompts.take(4))
    }

    // ── WhatsApp ───────────────────────────────────────────────────────────────

    private fun handleWhatsAppScreen(root: AccessibilityNodeInfo) {
        val messages = mutableListOf<String>()
        val messageNodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text")
        messageNodes?.forEach { msgNode ->
            val text = msgNode.text?.toString()
            if (!text.isNullOrBlank()) {
                val isOutgoing = isOutgoingMessage(msgNode.parent)
                messages.add("${if (isOutgoing) "Me" else "Them"}: $text")
            }
        }
        val conversation = messages.takeLast(10).joinToString("\n")

        if (conversation.isNotBlank() && conversation != lastConversation) {
            lastConversation = conversation
            // Persist WhatsApp conversation too
            val contactName = root
                .findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
                ?.firstOrNull()?.text?.toString() ?: ""
            if (contactName.isNotBlank()) {
                ContextStore.saveConversation(this, "wa_$contactName", conversation)
            }
            triggerSuggestions(conversation, "WhatsApp", "")
        }
    }

    private fun isOutgoingMessage(node: AccessibilityNodeInfo?): Boolean {
        val desc = node?.contentDescription?.toString() ?: return false
        return desc.contains("You") || desc.contains("sent")
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun collectTexts(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        minLen: Int = 1,
        maxLen: Int = 800
    ) {
        if (node.childCount == 0) {
            val text = node.text?.toString()
            if (!text.isNullOrBlank() && text.length in minLen..maxLen) out.add(text)
            return
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, out, minLen, maxLen) }
        }
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findEditText(it) }
            if (found != null) return found
        }
        return null
    }

    private fun triggerSuggestions(conversation: String, appName: String, profileContext: String) {
        val intent = Intent(this, FloatingWindowService::class.java).apply {
            action = FloatingWindowService.ACTION_SHOW_SUGGESTIONS
            putExtra(FloatingWindowService.EXTRA_CONVERSATION, conversation)
            putExtra(FloatingWindowService.EXTRA_APP_NAME, appName)
            putExtra(FloatingWindowService.EXTRA_PROFILE_CONTEXT, profileContext)
        }
        startService(intent)
    }

    override fun onInterrupt() {
        debounceJob?.cancel()
    }
}
