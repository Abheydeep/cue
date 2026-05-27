package com.abhey.cue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FloatingWindowService : Service() {

    companion object {
        const val ACTION_SHOW_SUGGESTIONS = "SHOW_SUGGESTIONS"
        const val EXTRA_CONVERSATION = "conversation"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_PROFILE_CONTEXT = "profile_context"
        private const val CHANNEL_ID = "cue_app_channel"
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_SUGGESTIONS) {
            val conversation = intent.getStringExtra(EXTRA_CONVERSATION) ?: return START_STICKY
            val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "App"
            val profileContext = intent.getStringExtra(EXTRA_PROFILE_CONTEXT) ?: ""

            showLoadingBubble()
            fetchAndShowSuggestions(conversation, appName, profileContext)
        }
        return START_STICKY
    }

    private fun fetchAndShowSuggestions(conversation: String, appName: String, profileContext: String) {
        scope.launch {
            val suggestions = GeminiService.getSuggestions(conversation, appName, profileContext)
            if (suggestions.isNotEmpty()) {
                showSuggestionBubble(suggestions)
            } else {
                removeFloatingView()
                Toast.makeText(this@FloatingWindowService, "Couldn't get suggestions", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLoadingBubble() {
        removeFloatingView()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 16
            y = 120
        }

        val loadingView = TextView(this).apply {
            text = "✨ Thinking..."
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.parseColor("#CC1a1a2e"))
        }

        floatingView = loadingView
        windowManager?.addView(loadingView, params)
    }

    private fun showSuggestionBubble(suggestions: List<String>) {
        removeFloatingView()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 0
        }

        // Build suggestion panel programmatically
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0F0F0"))
            setPadding(0, 8, 0, 8)
        }

        // Header row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 8, 12, 4)
        }

        val title = TextView(this).apply {
            text = "✨ Cue"
            setTextColor(Color.parseColor("#1a1a2e"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.GRAY)
            textSize = 16f
            setPadding(16, 0, 8, 0)
            setOnClickListener { removeFloatingView() }
        }

        header.addView(title)
        header.addView(closeBtn)
        container.addView(header)

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#DDDDDD"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
        }
        container.addView(divider)

        // Add each suggestion as a tappable row
        val labels = listOf("Direct", "Playful", "Ask back")
        suggestions.forEachIndexed { index, reply ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 10, 20, 10)
                setBackgroundColor(Color.WHITE)
                isClickable = true

                setOnClickListener {
                    // Copy to clipboard
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("reply", reply))
                    Toast.makeText(this@FloatingWindowService, "Copied! Paste in chat", Toast.LENGTH_SHORT).show()
                    removeFloatingView()
                }
            }

            val label = TextView(this).apply {
                text = labels.getOrElse(index) { "Option ${index + 1}" }
                setTextColor(Color.parseColor("#888888"))
                textSize = 10f
            }

            val replyText = TextView(this).apply {
                text = reply
                setTextColor(Color.parseColor("#1a1a2e"))
                textSize = 14f
            }

            row.addView(label)
            row.addView(replyText)

            // Separator between rows
            if (index < suggestions.size - 1) {
                val sep = View(this).apply {
                    setBackgroundColor(Color.parseColor("#F5F5F5"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                }
                container.addView(row)
                container.addView(sep)
            } else {
                container.addView(row)
            }
        }

        floatingView = container
        windowManager?.addView(container, params)
    }

    private fun removeFloatingView() {
        floatingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) { }
            floatingView = null
        }
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "Cue", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cue Active")
            .setContentText("Context-aware reply suggestions")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        removeFloatingView()
    }
}
