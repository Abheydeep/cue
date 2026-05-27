package com.abhey.cue

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ChatAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var debounceJob: Job? = null
    private var lastConversation = ""
    private var currentHingeKey = ""

    // Overlay state
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    private val supportedApps = mapOf(
        "com.whatsapp" to "WhatsApp",
        "co.hinge" to "Hinge"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // Restore saved API key on service start
        val key = getSharedPreferences("cue_app", MODE_PRIVATE).getString("api_key", "") ?: ""
        if (key.isNotBlank()) GeminiService.setApiKey(key)
    }

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
            val profile = extractHingeProfile(root)
            if (profile.name.isNotBlank()) {
                currentHingeKey = profile.name
                ContextStore.saveProfile(this, profile.name, profile.toContextString())
            }
            return
        }

        val messages = mutableListOf<String>()
        collectTexts(root, messages, minLen = 2, maxLen = 500)
        val conversation = messages.takeLast(10).joinToString("\n")

        if (conversation.isNotBlank() && conversation != lastConversation) {
            lastConversation = conversation
            if (currentHingeKey.isNotBlank()) ContextStore.saveConversation(this, currentHingeKey, conversation)
            showSuggestions(conversation, "Hinge", ContextStore.getProfile(this, currentHingeKey))
        }
    }

    private fun isHingeChatScreen(root: AccessibilityNodeInfo): Boolean {
        if (!root.findAccessibilityNodeInfosByViewId("co.hinge:id/message_edit_text").isNullOrEmpty()) return true
        return findEditText(root) != null
    }

    data class HingeProfile(val name: String, val age: String, val job: String, val prompts: List<Pair<String, String>>) {
        fun toContextString() = buildString {
            if (name.isNotBlank()) appendLine("Name: $name${if (age.isNotBlank()) ", $age" else ""}")
            if (job.isNotBlank()) appendLine("Job: $job")
            if (prompts.isNotEmpty()) { appendLine("Prompts:"); prompts.forEach { (q, a) -> appendLine("  Q: $q\n  A: $a") } }
        }.trim()
    }

    private val hingePromptKeywords = listOf(
        "I'm looking for", "My love language", "A life goal", "I go crazy for", "We'll get along",
        "My most irrational fear", "Typical Sunday", "Two truths and a lie", "I geek out on",
        "The key to my heart", "I recently discovered", "Most spontaneous thing", "I'll fall for you if"
    )

    private fun extractHingeProfile(root: AccessibilityNodeInfo): HingeProfile {
        val texts = mutableListOf<String>()
        collectTexts(root, texts)
        var name = ""; var age = ""; var job = ""
        val prompts = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < texts.size) {
            val t = texts[i]
            if (name.isEmpty() && t.matches(Regex("[A-Z][a-zA-Z ]+(,\\s*\\d{2})?"))) {
                val p = t.split(",").map { it.trim() }
                name = p[0]; if (p.size > 1 && p[1].all { it.isDigit() }) age = p[1]; i++; continue
            }
            if (age.isEmpty() && t.matches(Regex("\\d{2}"))) { age = t; i++; continue }
            val mp = hingePromptKeywords.firstOrNull { t.contains(it, ignoreCase = true) }
            if (mp != null && i + 1 < texts.size && texts[i+1].length in 2..300) { prompts.add(t to texts[i+1]); i += 2; continue }
            i++
        }
        return HingeProfile(name, age, job, prompts.take(4))
    }

    // ── WhatsApp ───────────────────────────────────────────────────────────────

    private fun handleWhatsAppScreen(root: AccessibilityNodeInfo) {
        val messages = mutableListOf<String>()
        root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/message_text")?.forEach { node ->
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                messages.add("${if (isOutgoingMessage(node.parent)) "Me" else "Them"}: $it")
            }
        }
        val conversation = messages.takeLast(10).joinToString("\n")
        if (conversation.isNotBlank() && conversation != lastConversation) {
            lastConversation = conversation
            showSuggestions(conversation, "WhatsApp", "")
        }
    }

    private fun isOutgoingMessage(node: AccessibilityNodeInfo?): Boolean {
        val desc = node?.contentDescription?.toString() ?: return false
        return desc.contains("You") || desc.contains("sent")
    }

    // ── Overlay ────────────────────────────────────────────────────────────────

    private fun showSuggestions(conversation: String, appName: String, profileContext: String) {
        removeOverlay()
        showLoadingDot()
        scope.launch {
            val replies = GeminiService.getSuggestions(conversation, appName, profileContext)
            removeOverlay()
            if (replies.isNotEmpty()) showPanel(replies)
        }
    }

    private fun showLoadingDot() {
        val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END, xOff = 24, yOff = 140)
        val view = TextView(this).apply {
            text = "✦ thinking..."
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(28, 14, 28, 14)
            background = roundedBg(Color.parseColor("#CC101827"))
        }
        overlayView = view
        try { windowManager?.addView(view, params) } catch (_: Exception) {}
    }

    private fun showPanel(replies: List<String>) {
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F7F7"))
            setPadding(0, 6, 0, 6)
        }

        // Header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(20, 8, 12, 4) }
        header.addView(TextView(this).apply {
            text = "Cue"
            setTextColor(Color.parseColor("#101827"))
            textSize = 11f
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 15f
            setPadding(20, 0, 12, 0)
            setOnClickListener { removeOverlay() }
        })
        container.addView(header)

        // Thin divider
        container.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#E5E5E5"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        val labels = listOf("Direct", "Light", "Ask back")
        replies.forEachIndexed { idx, reply ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 12, 20, 12)
                setBackgroundColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    val cb = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("cue", reply))
                    Toast.makeText(this@ChatAccessibilityService, "Copied — paste and send", Toast.LENGTH_SHORT).show()
                    removeOverlay()
                }
            }
            row.addView(TextView(this).apply {
                text = labels.getOrElse(idx) { "Option ${idx+1}" }
                setTextColor(Color.parseColor("#999999"))
                textSize = 10f
            })
            row.addView(TextView(this).apply {
                text = reply
                setTextColor(Color.parseColor("#101827"))
                textSize = 14f
                setPadding(0, 3, 0, 0)
            })
            container.addView(row)
            if (idx < replies.size - 1) {
                container.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#F0F0F0"))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                })
            }
        }

        overlayView = container
        try { windowManager?.addView(container, params) } catch (_: Exception) {}
    }

    private fun removeOverlay() {
        overlayView?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        overlayView = null
    }

    private fun overlayParams(w: Int, h: Int, gravity: Int, xOff: Int = 0, yOff: Int = 0) =
        WindowManager.LayoutParams(w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { this.gravity = gravity; x = xOff; y = yOff }

    private fun roundedBg(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = 32f
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, minLen: Int = 1, maxLen: Int = 800) {
        if (node.childCount == 0) {
            node.text?.toString()?.takeIf { it.length in minLen..maxLen }?.let { out.add(it) }
            return
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { collectTexts(it, out, minLen, maxLen) }
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.className?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) { val f = node.getChild(i)?.let { findEditText(it) }; if (f != null) return f }
        return null
    }

    override fun onInterrupt() { debounceJob?.cancel(); removeOverlay() }
    override fun onDestroy() { super.onDestroy(); removeOverlay() }
}
