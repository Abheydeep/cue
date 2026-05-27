package com.abhey.cue

import android.content.Intent
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
        val statusText = findViewById<TextView>(R.id.tvStatus)

        val prefs = getSharedPreferences("cue_app", MODE_PRIVATE)
        val defaultKey = "AIzaSyDfFJi4taond4zpFTsVl8zuT1hPpT1gQpw"
        val savedKey = prefs.getString("api_key", defaultKey) ?: defaultKey
        if (prefs.getString("api_key", null) == null) prefs.edit().putString("api_key", defaultKey).apply()
        apiKeyInput.setText(savedKey)
        GeminiService.setApiKey(savedKey)

        saveBtn.setOnClickListener {
            val key = apiKeyInput.text.toString().trim()
            if (key.isBlank()) { Toast.makeText(this, "Enter API key", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            prefs.edit().putString("api_key", key).apply()
            GeminiService.setApiKey(key)
            Toast.makeText(this, "Saved!", Toast.LENGTH_SHORT).show()
            updateStatus(statusText)
        }

        accessibilityBtn.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        updateStatus(statusText)
    }

    override fun onResume() {
        super.onResume()
        updateStatus(findViewById(R.id.tvStatus))
    }

    private fun updateStatus(tv: TextView) {
        val ok = isAccessibilityEnabled()
        val keyOk = getSharedPreferences("cue_app", MODE_PRIVATE).getString("api_key", "").isNullOrBlank().not()
        tv.text = buildString {
            appendLine("API Key: ${if (keyOk) "✅ Ready" else "❌ Not set"}")
            appendLine("Accessibility: ${if (ok) "✅ Active" else "❌ Enable it"}")
            appendLine()
            append(if (ok && keyOk) "🟢 All set — open WhatsApp or Hinge and chat." else "Complete the steps above to activate.")
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "$packageName/${ChatAccessibilityService::class.java.canonicalName}"
        return Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(service) == true
    }
}
