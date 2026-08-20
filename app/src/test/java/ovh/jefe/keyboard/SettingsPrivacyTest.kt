package ovh.jefe.keyboard

import android.content.pm.ApplicationInfo
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import androidx.annotation.XmlRes
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowToast
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
class SettingsPrivacyTest {
    @Before
    fun clearPreferences() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    @Test
    fun `rejects an insecure service URL with an actionable toast`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        activity.supportFragmentManager.executePendingTransactions()
        val fragment = requireNotNull(
            activity.supportFragmentManager.findFragmentById(R.id.settings_container)
                as? SettingsActivity.SettingsFragment,
        )
        val preference = requireNotNull(fragment.findPreference<EditTextPreference>("whisper_url"))

        val accepted = preference.callChangeListener("http://voice.local:8080")

        assertFalse(accepted)
        assertEquals("Connexion non sécurisée refusée. Utilisez HTTPS.", ShadowToast.getTextOfLatestToast())
        assertEquals("", preference.text.orEmpty())
    }

    @Test
    fun `refreshes setup status after an accepted URL is persisted`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        activity.supportFragmentManager.executePendingTransactions()
        val fragment = requireNotNull(
            activity.supportFragmentManager.findFragmentById(R.id.settings_container)
                as? SettingsActivity.SettingsFragment,
        )
        val preference = requireNotNull(fragment.findPreference<EditTextPreference>("whisper_url"))
        val setupPreference = requireNotNull(fragment.findPreference<Preference>("setup_whisper"))
        assertEquals("À terminer · toucher pour ouvrir", setupPreference.summary)

        assertTrue(preference.callChangeListener("https://voice.example.test/base/"))
        preference.text = "https://voice.example.test/base/"
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals("✅ Configuré", setupPreference.summary)
    }

    @Test
    fun `API keys use status summaries and password editors`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        activity.supportFragmentManager.executePendingTransactions()
        val fragment = requireNotNull(
            activity.supportFragmentManager.findFragmentById(R.id.settings_container)
                as? SettingsActivity.SettingsFragment,
        )

        listOf("whisper_api_key", "translate_api_key").forEach { key ->
            val preference = requireNotNull(fragment.findPreference<EditTextPreference>(key))
            preference.text = "secret-$key"
            assertEquals("Configurée", preference.summary)
            preference.text = ""
            assertEquals("Non configurée", preference.summary)

            fragment.onDisplayPreferenceDialog(preference)
            activity.supportFragmentManager.executePendingTransactions()
            val dialogFragment = requireNotNull(
                activity.supportFragmentManager.findFragmentByTag(
                    "androidx.preference.PreferenceFragment.DIALOG",
                ) as? androidx.fragment.app.DialogFragment,
            )
            val editor = requireNotNull(dialogFragment.requireDialog().findViewById<EditText>(android.R.id.edit))

            assertEquals(InputType.TYPE_CLASS_TEXT, editor.inputType and InputType.TYPE_MASK_CLASS)
            assertEquals(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                editor.inputType and InputType.TYPE_MASK_VARIATION,
            )
            assertTrue(editor.transformationMethod is PasswordTransformationMethod)
            dialogFragment.dismissNow()
        }
    }

    @Test
    fun `application excludes preferences from cloud backup and device transfer`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val manifest = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(mergedManifest())
        val application = manifest.getElementsByTagName("application").item(0)
        val androidNamespace = "http://schemas.android.com/apk/res/android"

        assertEquals(0, activity.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertEquals("false", application.attributes.getNamedItemNS(androidNamespace, "allowBackup").nodeValue)
        assertEquals(
            "@xml/backup_rules",
            application.attributes.getNamedItemNS(androidNamespace, "fullBackupContent").nodeValue,
        )
        assertEquals(
            "@xml/data_extraction_rules",
            application.attributes.getNamedItemNS(androidNamespace, "dataExtractionRules").nodeValue,
        )
        assertEquals(
            setOf("full-backup-content:sharedpref:."),
            backupExclusions(activity, R.xml.backup_rules),
        )
        assertEquals(
            setOf(
                "cloud-backup:sharedpref:.",
                "device-transfer:sharedpref:.",
            ),
            backupExclusions(activity, R.xml.data_extraction_rules),
        )
    }

    private fun mergedManifest(): File =
        generateSequence(File(requireNotNull(System.getProperty("user.dir")))) {
            it.parentFile ?: return@generateSequence null
        }.map {
            File(
                it,
                "app/build/intermediates/merged_manifest/debug/" +
                    "processDebugMainManifest/AndroidManifest.xml",
            )
        }.first(File::isFile)

    private fun backupExclusions(
        context: android.content.Context,
        @XmlRes resourceId: Int,
    ): Set<String> {
        val exclusions = mutableSetOf<String>()
        var section = ""
        context.resources.getXml(resourceId).use { parser ->
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "full-backup-content", "cloud-backup", "device-transfer" -> {
                            section = parser.name
                        }

                        "exclude" -> exclusions += listOf(
                            section,
                            parser.getAttributeValue(null, "domain"),
                            parser.getAttributeValue(null, "path"),
                        ).joinToString(":")
                    }

                    XmlPullParser.END_TAG -> if (parser.name == section) section = ""
                }
                parser.next()
            }
        }
        return exclusions
    }
}
