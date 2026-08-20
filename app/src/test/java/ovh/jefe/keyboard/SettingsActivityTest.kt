package ovh.jefe.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsActivityTest {
    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, null)
    }

    @Test
    fun `settings screen has one scrolling preference surface and setup header`() {
        val activity = createActivity()

        val setupHeaderId = activity.resources.getIdentifier("setup_header", "id", activity.packageName)
        assertNotEquals(0, setupHeaderId)
        assertNotNull(activity.findViewById<View>(setupHeaderId))
        assertNotNull(activity.supportFragmentManager.findFragmentById(R.id.settings_container))
        assertEquals(0, countViews(activity.window.decorView, ScrollView::class.java))
        assertEquals(1, countViews(activity.window.decorView, RecyclerView::class.java))
    }

    @Test
    fun `setup completion refreshes after shared preferences change`() {
        val activity = createActivity()
        val progressId = activity.resources.getIdentifier("setup_progress_text", "id", activity.packageName)
        assertNotEquals(0, progressId)
        val progress = activity.findViewById<TextView>(progressId)
        assertEquals("0 sur 5 étapes terminées", progress.text.toString())

        PreferenceManager.getDefaultSharedPreferences(activity)
            .edit()
            .putString("whisper_url", "https://voice.example.test/base/")
            .putString("translate_url", "https://translate.example.test/")
            .commit()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals("2 sur 5 étapes terminées", progress.text.toString())
        val fragment = settingsFragment(activity)
        assertEquals("✅ Configuré", fragment.findPreference<Preference>("setup_whisper")?.summary)
        assertEquals("✅ Configuré", fragment.findPreference<Preference>("setup_translate")?.summary)
    }

    @Test
    fun `setup header stays in progress until every step is complete`() {
        val activity = createActivity()
        val badge = activity.findViewById<TextView>(R.id.setup_status_badge)

        activity.renderSetup(
            SettingsActivity.SetupStatus(
                imeEnabled = false,
                imeDefault = false,
                microphoneGranted = false,
                whisperConfigured = true,
                translateConfigured = true,
            ),
        )

        assertEquals("Configuration en cours", badge.text.toString())
        assertEquals(ContextCompat.getColor(activity, R.color.settings_pending), badge.currentTextColor)

        activity.renderSetup(
            SettingsActivity.SetupStatus(
                imeEnabled = true,
                imeDefault = true,
                microphoneGranted = true,
                whisperConfigured = true,
                translateConfigured = true,
            ),
        )

        assertEquals("Prêt à écrire", badge.text.toString())
        assertEquals(ContextCompat.getColor(activity, R.color.settings_success), badge.currentTextColor)
    }

    @Test
    fun `setup refreshes when focus returns from the input method picker`() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java)
        val uncreatedActivity = controller.get()
        uncreatedActivity.onWindowFocusChanged(true)

        val activity = controller.setup().get().also {
            it.supportFragmentManager.executePendingTransactions()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        val progress = activity.findViewById<TextView>(R.id.setup_progress_text)
        val defaultRow = settingsFragment(activity).findPreference<Preference>("setup_default_ime")
        assertEquals("0 sur 5 étapes terminées", progress.text.toString())

        activity.onWindowFocusChanged(false)
        Settings.Secure.putString(
            activity.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
            "${activity.packageName}/.JefeKeyboardService",
        )
        activity.onWindowFocusChanged(true)

        assertEquals("1 sur 5 étapes terminées", progress.text.toString())
        assertEquals("✅ Configuré", defaultRow?.summary)
    }

    @Test
    fun `default keyboard status requires the exact service component`() {
        val activity = createActivity()

        listOf(
            "${activity.packageName}.lookalike/.JefeKeyboardService",
            "${activity.packageName}/.DifferentKeyboardService",
        ).forEach { component ->
            Settings.Secure.putString(
                activity.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                component,
            )

            assertFalse(activity.setupStatus().imeDefault)
        }

        Settings.Secure.putString(
            activity.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
            "${activity.packageName}/.JefeKeyboardService",
        )

        assertTrue(activity.setupStatus().imeDefault)
    }

    @Test
    fun `night setup header secondary copy meets contrast target`() {
        val darkConfiguration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_YES
        }
        val darkContext = context.createConfigurationContext(darkConfiguration)
        val copy = ContextCompat.getColor(darkContext, R.color.secondary_text)
        val surface = ContextCompat.getColor(darkContext, R.color.settings_header_surface)

        assertTrue(ColorUtils.calculateContrast(copy, surface) >= 4.5)
    }

    @Test
    fun `API key summaries never render stored secrets`() {
        val secret = "top-secret-value"
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString("whisper_api_key", secret)
            .putString("translate_api_key", secret)
            .commit()
        val fragment = settingsFragment(createActivity())

        listOf("whisper_api_key", "translate_api_key").forEach { key ->
            val preference = requireNotNull(fragment.findPreference<EditTextPreference>(key))
            assertEquals("Configurée", preference.summary)
            assertNotEquals(secret, preference.summary)
            assertFalse(allText(fragment.requireView()).contains(secret))
        }
    }

    @Test
    fun `setup action rows launch system setup and URL configuration`() {
        val activity = createActivity()
        val fragment = settingsFragment(activity)
        val enable = requireNotNull(fragment.findPreference<Preference>("setup_enable_ime"))
        val whisper = requireNotNull(fragment.findPreference<Preference>("setup_whisper"))

        enable.performClick()
        assertEquals(
            Settings.ACTION_INPUT_METHOD_SETTINGS,
            Shadows.shadowOf(activity).nextStartedActivity.action,
        )

        whisper.performClick()
        activity.supportFragmentManager.executePendingTransactions()
        val dialog = activity.supportFragmentManager.findFragmentByTag(
            "androidx.preference.PreferenceFragment.DIALOG",
        )
        assertNotNull(dialog)
    }

    @Test
    fun `setup rows retain comfortable touch targets`() {
        val activity = createActivity()
        val recycler = findFirst(activity.window.decorView, RecyclerView::class.java)
        measureAndLayout(activity.window.decorView, 1080, 1920)
        recycler.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
        )
        recycler.layout(0, 0, 1080, 1600)

        val minimum = 44f * activity.resources.displayMetrics.density
        val actionRows = (0 until recycler.childCount)
            .map(recycler::getChildAt)
            .filter { child ->
                val text = allText(child)
                text.any { it.startsWith("1.") || it.startsWith("2.") || it.startsWith("3.") }
            }
        assertTrue(actionRows.isNotEmpty())
        assertTrue(actionRows.all { it.height >= minimum })
    }

    @Test
    @Config(qualifiers = "notnight")
    fun `render light settings screenshot`() {
        renderSettings("settings-light.png")
    }

    @Test
    @Config(qualifiers = "night")
    fun `render dark settings screenshot`() {
        renderSettings("settings-dark.png")
    }

    private fun renderSettings(fileName: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString("whisper_url", "https://voice.example.test/")
            .putString("translate_url", "https://translate.example.test/")
            .commit()
        val activity = createActivity()
        val decor = activity.window.decorView
        measureAndLayout(decor, 1080, 1920)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(bitmap))
        assertHasVisualContent(bitmap)
        System.getenv("VISUAL_OUTPUT_DIR")?.let(::File)?.also(File::mkdirs)?.resolve(fileName)?.let { file ->
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(file.isFile && file.length() > 0)
        }
    }

    private fun createActivity(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also {
            it.supportFragmentManager.executePendingTransactions()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }

    private fun settingsFragment(activity: SettingsActivity): SettingsActivity.SettingsFragment =
        requireNotNull(
            activity.supportFragmentManager.findFragmentById(R.id.settings_container)
                as? SettingsActivity.SettingsFragment,
        )

    private fun countViews(view: View, type: Class<out View>): Int {
        val own = if (type.isInstance(view)) 1 else 0
        if (view !is ViewGroup) return own
        return own + (0 until view.childCount).sumOf { countViews(view.getChildAt(it), type) }
    }

    private fun <T : View> findFirst(view: View, type: Class<T>): T {
        if (type.isInstance(view)) return type.cast(view)
        require(view is ViewGroup)
        for (index in 0 until view.childCount) {
            runCatching { return findFirst(view.getChildAt(index), type) }
        }
        error("${type.simpleName} not found")
    }

    private fun allText(view: View): List<String> {
        val own = if (view is TextView) listOf(view.text.toString()) else emptyList()
        if (view !is ViewGroup) return own
        return own + (0 until view.childCount).flatMap { allText(view.getChildAt(it)) }
    }

    private fun measureAndLayout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    private fun assertHasVisualContent(bitmap: Bitmap) {
        val sampledColors = buildSet {
            for (y in 0 until bitmap.height step 24) {
                for (x in 0 until bitmap.width step 24) add(bitmap.getPixel(x, y))
            }
        }
        assertTrue("Rendered settings must contain more than a blank surface", sampledColors.size > 4)
    }
}
