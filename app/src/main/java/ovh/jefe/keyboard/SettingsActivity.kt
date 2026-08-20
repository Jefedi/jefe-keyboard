package ovh.jefe.keyboard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager

/** Setup and private-service settings with a single scrolling preference surface. */
class SettingsActivity : AppCompatActivity(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var preferences: SharedPreferences
    private lateinit var setupProgressText: TextView
    private lateinit var setupProgressBar: ProgressBar
    private lateinit var setupStatusBadge: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        setupProgressText = findViewById(R.id.setup_progress_text)
        setupProgressBar = findViewById(R.id.setup_progress_bar)
        setupStatusBadge = findViewById(R.id.setup_status_badge)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
        refreshSetup()
    }

    override fun onStart() {
        super.onStart()
        preferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::preferences.isInitialized) refreshSetup()
    }

    override fun onStop() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == KEY_WHISPER_URL || key == KEY_TRANSLATE_URL) refreshSetup()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refreshSetup()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Toast.makeText(
            this,
            if (granted) R.string.microphone_granted else R.string.microphone_denied,
            if (granted) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
        ).show()
        refreshSetup()
    }

    internal fun refreshSetup() {
        if (!::setupProgressText.isInitialized) return
        renderSetup(setupStatus())
    }

    internal fun renderSetup(status: SetupStatus) {
        setupProgressText.text = getString(R.string.setup_progress_format, status.completed)
        setupProgressBar.progress = status.completed
        val isReady = status.completed == SETUP_STEP_COUNT
        setupStatusBadge.text = getString(
            if (isReady) R.string.setup_status_ready else R.string.setup_status_in_progress,
        )
        setupStatusBadge.setTextColor(
            ContextCompat.getColor(this, if (isReady) R.color.settings_success else R.color.slate),
        )
        setupStatusBadge.visibility = View.VISIBLE
        (supportFragmentManager.findFragmentById(R.id.settings_container) as? SettingsFragment)
            ?.renderSetupStatus(status)
    }

    internal fun setupStatus(): SetupStatus = SetupStatus(
        imeEnabled = isImeEnabled(),
        imeDefault = isImeDefault(),
        microphoneGranted = hasMicrophonePermission(),
        whisperConfigured = endpointConfigured(KEY_WHISPER_URL),
        translateConfigured = endpointConfigured(KEY_TRANSLATE_URL),
    )

    private fun isImeEnabled(): Boolean {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        return manager.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun isImeDefault(): Boolean {
        val configured = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        )?.let(ComponentName::unflattenFromString)
        return configured == ComponentName(this, JefeKeyboardService::class.java)
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun endpointConfigured(key: String): Boolean =
        ServiceEndpoint.parse(preferences.getString(key, "").orEmpty()) is RemoteResult.Success

    internal fun openImeSettings() {
        runCatching {
            startActivityForResult(
                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                REQUEST_IME_ENABLED,
            )
        }.onFailure {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    internal fun openInputMethodPicker() {
        runCatching {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            manager.showInputMethodPicker()
        }.onFailure { openImeSettings() }
    }

    internal fun requestMicrophonePermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO,
        )
    }

    internal data class SetupStatus(
        val imeEnabled: Boolean,
        val imeDefault: Boolean,
        val microphoneGranted: Boolean,
        val whisperConfigured: Boolean,
        val translateConfigured: Boolean,
    ) {
        val completed: Int
            get() = listOf(
                imeEnabled,
                imeDefault,
                microphoneGranted,
                whisperConfigured,
                translateConfigured,
            ).count { it }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            configureSetupActions()
            configureUrlPreference(KEY_WHISPER_URL)
            configureUrlPreference(KEY_TRANSLATE_URL)
            configureApiKeyPreference(KEY_WHISPER_API_KEY)
            configureApiKeyPreference(KEY_TRANSLATE_API_KEY)
        }

        override fun onResume() {
            super.onResume()
            (activity as? SettingsActivity)?.refreshSetup()
        }

        private fun configureSetupActions() {
            action(SETUP_ENABLE_IME) { host().openImeSettings() }
            action(SETUP_DEFAULT_IME) { host().openInputMethodPicker() }
            action(SETUP_MICROPHONE) { host().requestMicrophonePermission() }
            action(SETUP_WHISPER) { openEditor(KEY_WHISPER_URL) }
            action(SETUP_TRANSLATE) { openEditor(KEY_TRANSLATE_URL) }
        }

        private fun openEditor(key: String) {
            findPreference<EditTextPreference>(key)?.let(::onDisplayPreferenceDialog)
        }

        private fun action(key: String, block: () -> Unit) {
            findPreference<Preference>(key)?.onPreferenceClickListener =
                Preference.OnPreferenceClickListener {
                    block()
                    true
                }
        }

        private fun host(): SettingsActivity = requireActivity() as SettingsActivity

        private fun configureUrlPreference(key: String) {
            val preference = findPreference<EditTextPreference>(key) ?: return
            preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                when (val result = ServiceEndpoint.parse(newValue as? String ?: "")) {
                    is RemoteResult.Success -> {
                        view?.post { (activity as? SettingsActivity)?.refreshSetup() }
                        true
                    }

                    is RemoteResult.Failure -> {
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                        false
                    }
                }
            }
        }

        private fun configureApiKeyPreference(key: String) {
            val preference = findPreference<EditTextPreference>(key) ?: return
            preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> { apiKey ->
                getString(
                    if (apiKey.text.isNullOrEmpty()) {
                        R.string.api_key_not_configured
                    } else {
                        R.string.api_key_configured
                    },
                )
            }
            preference.setOnBindEditTextListener { editor ->
                editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                editor.transformationMethod = PasswordTransformationMethod.getInstance()
                editor.setSelection(editor.text.length)
            }
        }

        internal fun renderSetupStatus(status: SetupStatus) {
            setStatus(SETUP_ENABLE_IME, status.imeEnabled)
            setStatus(SETUP_DEFAULT_IME, status.imeDefault)
            setStatus(SETUP_MICROPHONE, status.microphoneGranted)
            setStatus(SETUP_WHISPER, status.whisperConfigured)
            setStatus(SETUP_TRANSLATE, status.translateConfigured)
        }

        private fun setStatus(key: String, complete: Boolean) {
            findPreference<Preference>(key)?.summary = getString(
                if (complete) R.string.setup_status_done else R.string.setup_status_pending,
            )
        }
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 101
        const val REQUEST_IME_ENABLED = 102
        const val KEY_WHISPER_URL = "whisper_url"
        const val KEY_TRANSLATE_URL = "translate_url"
        const val KEY_WHISPER_API_KEY = "whisper_api_key"
        const val KEY_TRANSLATE_API_KEY = "translate_api_key"
        const val SETUP_ENABLE_IME = "setup_enable_ime"
        const val SETUP_DEFAULT_IME = "setup_default_ime"
        const val SETUP_MICROPHONE = "setup_microphone"
        const val SETUP_WHISPER = "setup_whisper"
        const val SETUP_TRANSLATE = "setup_translate"
        const val SETUP_STEP_COUNT = 5
    }
}
