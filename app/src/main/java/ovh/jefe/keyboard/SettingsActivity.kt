package ovh.jefe.keyboard

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

/**
 * Onboarding + Settings combinés.
 * Vérifie: IME activé → IME par défaut → permission micro → URLs configurées.
 * Guide l'utilisateur étape par étape comme Gboard, FlorisBoard ou AnySoftKeyboard.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val REQ_RECORD_AUDIO = 101
        private const val REQ_IME_ENABLED = 102
        private const val REQ_IME_DEFAULT = 103
    }

    private lateinit var statusImeEnabled: TextView
    private lateinit var statusImeDefault: TextView
    private lateinit var statusMicPermission: TextView
    private lateinit var statusWhisperUrl: TextView
    private lateinit var statusTranslateUrl: TextView

    private lateinit var btnEnableIme: Button
    private lateinit var btnSetDefault: Button
    private lateinit var btnGrantMic: Button
    private lateinit var btnConfigureUrls: Button

    private lateinit var progressIme: ProgressBar
    private lateinit var progressDefault: ProgressBar
    private lateinit var progressMic: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }

        // Ajouter la vue d'onboarding au-dessus des settings
        setupOnboarding()
    }

    private fun setupOnboarding() {
        val container = findViewById<LinearLayout>(R.id.onboarding_container)
            ?: return

        container.removeAllViews()

        // Titre
        val title = TextView(this).apply {
            text = "Configuration du clavier"
            textSize = 20f
            setPadding(48, 32, 48, 16)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        container.addView(title)

        // ─── Étape 1: Activer le clavier ───
        val step1Layout = createStepLayout(
            "1. Activer Jefe Keyboard",
            "Le clavier doit être activé dans les paramètres système.",
            "Activer le clavier",
            ::openImeSettings,
            ::isImeEnabled
        )
        statusImeEnabled = step1Layout.status
        btnEnableIme = step1Layout.button
        progressIme = step1Layout.progress
        container.addView(step1Layout.root)

        // ─── Étape 2: Définir comme clavier par défaut ───
        val step2Layout = createStepLayout(
            "2. Choisir comme clavier par défaut",
            "Sélectionnez Jefe Keyboard dans la liste des claviers.",
            "Changer de clavier",
            ::openDefaultImeSettings,
            ::isImeDefault
        )
        statusImeDefault = step2Layout.status
        btnSetDefault = step2Layout.button
        progressDefault = step2Layout.progress
        container.addView(step2Layout.root)

        // ─── Étape 3: Permission micro ───
        val step3Layout = createStepLayout(
            "3. Autoriser le microphone",
            "Nécessaire pour la dictée vocale (Whisper).",
            "Accorder la permission",
            ::requestMicPermission,
            ::hasMicPermission
        )
        statusMicPermission = step3Layout.status
        btnGrantMic = step3Layout.button
        progressMic = step3Layout.progress
        container.addView(step3Layout.root)

        // ─── Étape 4: Configurer Whisper ───
        val step4Layout = createStepLayout(
            "4. Configurer l'URL Whisper",
            "URL de votre serveur Whisper pour la dictée vocale.",
            null,
            null,
            ::isWhisperConfigured
        )
        statusWhisperUrl = step4Layout.status
        container.addView(step4Layout.root)

        // ─── Étape 5: Configurer LibreTranslate ───
        val step5Layout = createStepLayout(
            "5. Configurer l'URL LibreTranslate",
            "URL de votre serveur LibreTranslate pour la traduction.",
            null,
            null,
            ::isTranslateConfigured
        )
        statusTranslateUrl = step5Layout.status
        container.addView(step5Layout.root)

        // Séparateur
        val separator = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 2
            ).apply { setMargins(48, 32, 48, 32) }
            setBackgroundColor(0x22000000)
        }
        container.addView(separator)

        // Info
        val info = TextView(this).apply {
            text = "ℹ️ Les URLs et clés API se configurent dans les paramètres ci-dessous."
            textSize = 13f
            setPadding(48, 16, 48, 32)
            setTextColor(0xFF666666.toInt())
        }
        container.addView(info)
    }

    private data class StepLayout(
        val root: LinearLayout,
        val status: TextView,
        val button: Button,
        val progress: ProgressBar
    )

    private fun createStepLayout(
        title: String,
        description: String,
        buttonText: String?,
        buttonAction: (() -> Unit)?,
        checkFn: () -> Boolean
    ): StepLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }

        // Title row with checkmark/cross
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val progress = ProgressBar(this).apply {
            isIndeterminate = false
            max = 100
            progress = if (checkFn()) 100 else 0
            layoutParams = LinearLayout.LayoutParams(32.dp(), 32.dp()).apply {
                rightMargin = 16
            }
        }
        titleRow.addView(progress)

        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        titleRow.addView(titleView)
        layout.addView(titleRow)

        // Description
        val descView = TextView(this).apply {
            text = description
            textSize = 13f
            setPadding(48, 8, 0, 8)
            setTextColor(0xFF666666.toInt())
        }
        layout.addView(descView)

        // Status
        val status = TextView(this).apply {
            textSize = 13f
            setPadding(48, 4, 0, 8)
            val done = checkFn()
            text = if (done) "✅ Configuré" else "⏳ En attente"
            setTextColor(if (done) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
        }
        layout.addView(status)

        // Button (optional)
        if (buttonText != null && buttonAction != null) {
            val button = Button(this).apply {
                text = buttonText
                setOnClickListener { buttonAction() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(48, 8, 0, 8) }
            }
            layout.addView(button)

            return StepLayout(layout, status, button, progress)
        }

        // Placeholder button (hidden)
        val placeholder = Button(this).apply { visibility = View.GONE }
        return StepLayout(layout, status, placeholder, progress)
    }

    // ─── Checks ───
    private fun isImeEnabled(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val enabledImes = imm.enabledInputMethodList
        return enabledImes.any { it.packageName == packageName }
    }

    private fun isImeDefault(): Boolean {
        val currentIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        return currentIme != null && currentIme.contains(packageName)
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isWhisperConfigured(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val url = prefs.getString("whisper_url", "") ?: ""
        return url.isNotEmpty()
    }

    private fun isTranslateConfigured(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val url = prefs.getString("translate_url", "") ?: ""
        return url.isNotEmpty()
    }

    // ─── Actions ───
    private fun openImeSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivityForResult(intent, REQ_IME_ENABLED)
        } catch (e: Exception) {
            // Fallback: open general settings
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun openDefaultImeSettings() {
        try {
            // Android 11+: show input method picker dialog
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            // Fallback: open keyboard settings
            openImeSettings()
        }
    }

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQ_RECORD_AUDIO
        )
    }

    // ─── Résultats ───
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refreshOnboarding()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                Toast.makeText(this, "Micro autorisé ✅", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Micro refusé — la dictée ne marchera pas", Toast.LENGTH_LONG).show()
            }
        }
        refreshOnboarding()
    }

    override fun onResume() {
        super.onResume()
        refreshOnboarding()
    }

    private fun refreshOnboarding() {
        setupOnboarding()
    }

    // ─── Utils ───
    private fun Int.dp(): Int {
        val dm = resources.displayMetrics
        return (this * dm.density).toInt()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }
    }
}