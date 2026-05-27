package com.abhey.cue

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val apiKeyInput = findViewById<EditText>(R.id.etApiKey)
        val saveBtn = findViewById<Button>(R.id.btnSave)
        val accessibilityBtn = findViewById<Button>(R.id.btnAccessibility)
        val overlayBtn = findViewById<Button>(R.id.btnOverlay)
        val statusText = findViewById<TextView>(R.id.tvStatus)

        val prefs = getSharedPreferences("cue_app", MODE_PRIVATE)
        val savedKey = prefs.getString("api_key", "") ?: ""
        apiKeyInput.setText(savedKey)
        // Restore key into GeminiService after process restart
        if (savedKey.isNotBlank()) GeminiService.setApiKey(savedKey)

        // Save API key
        saveBtn.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "Enter your Gemini API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString("api_key", key).apply()
            // Inject into GeminiService at runtime
            GeminiService.setApiKey(key)
            Toast.makeText(this, "API key saved!", Toast.LENGTH_SHORT).show()
            updateStatus(statusText)
        }

        // Open Accessibility Settings
        accessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Open Overlay Permission
        overlayBtn.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        updateStatus(statusText)
    }

    override fun onResume() {
        super.onResume()
        val statusText = findViewById<TextView>(R.id.tvStatus)
        updateStatus(statusText)
    }

    private fun updateStatus(tv: TextView) {
        val overlayOk = Settings.canDrawOverlays(this)
        val accessibilityOk = isAccessibilityEnabled()
        val apiKeyOk = getSharedPreferences("cue_app", MODE_PRIVATE)
            .getString("api_key", "").isNullOrBlank().not()

        tv.text = """
            API Key: ${if (apiKeyOk) "✅ Saved" else "❌ Not set"}
            Overlay Permission: ${if (overlayOk) "✅ Granted" else "❌ Required"}
            Accessibility Service: ${if (accessibilityOk) "✅ Active" else "❌ Enable it"}
            
            ${if (overlayOk && accessibilityOk && apiKeyOk) 
                "🟢 All set! Open WhatsApp or Hinge and chat." 
            else 
                "Complete the steps above to activate."}
        """.trimIndent()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "$packageName/${ChatAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }
}
