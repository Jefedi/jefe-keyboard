package ovh.jefe.keyboard

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InkThemeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val lightContext = context.withNightMode(Configuration.UI_MODE_NIGHT_NO)
    private val darkContext = context.withNightMode(Configuration.UI_MODE_NIGHT_YES)

    @Test
    fun `approved light and dark palettes meet text contrast`() {
        assertPalette(
            lightContext,
            mapOf(
                R.color.paper to "#F4F6F5",
                R.color.ink to "#142934",
                R.color.mist to "#D7E0E0",
                R.color.slate to "#68808A",
                R.color.secondary_text to "#49616B",
                R.color.pen_blue to "#2E5C9A",
                R.color.recording_red to "#C84B48",
                R.color.elevated_surface to "#E8EEED",
                R.color.divider to "#C9D5D4",
                R.color.on_accent to "#FFFFFF",
                R.color.on_recording to "#FFFFFF",
                R.color.key_bg to "#EEF2F1",
                R.color.key_bg_special to "#E3EAE9",
            ),
        )
        assertPalette(
            darkContext,
            mapOf(
                R.color.paper to "#101719",
                R.color.ink to "#EAF0EF",
                R.color.mist to "#314249",
                R.color.slate to "#829596",
                R.color.secondary_text to "#AAB8B7",
                R.color.pen_blue to "#7DA9E8",
                R.color.recording_red to "#FF8A86",
                R.color.elevated_surface to "#223038",
                R.color.divider to "#314249",
                R.color.on_accent to "#101719",
                R.color.on_recording to "#101719",
                R.color.key_bg to "#1A252A",
                R.color.key_bg_special to "#223038",
            ),
        )

        assertContrast(lightContext, R.color.ink, R.color.paper)
        assertContrast(lightContext, R.color.secondary_text, R.color.paper)
        assertContrast(lightContext, R.color.ink, R.color.mist)
        assertContrast(lightContext, R.color.on_accent, R.color.pen_blue)
        assertContrast(lightContext, R.color.on_recording, R.color.recording_red)
        assertContrast(darkContext, R.color.key_text, R.color.keyboard_surface)
        assertContrast(darkContext, R.color.secondary_text, R.color.keyboard_surface)
        assertContrast(darkContext, R.color.key_text, R.color.key_pressed)
        assertContrast(darkContext, R.color.recording_red, R.color.keyboard_surface)
        assertContrast(darkContext, R.color.on_accent, R.color.pen_blue)
        assertContrast(darkContext, R.color.on_recording, R.color.recording_red)
    }

    @Test
    fun `semantic aliases resolve to the approved system roles`() {
        listOf(lightContext, darkContext).forEach { themedContext ->
            assertEquals(color(themedContext, R.color.paper), color(themedContext, R.color.keyboard_surface))
            assertEquals(color(themedContext, R.color.ink), color(themedContext, R.color.key_text))
            assertEquals(color(themedContext, R.color.ink), color(themedContext, R.color.suggestion_text))
            assertEquals(color(themedContext, R.color.mist), color(themedContext, R.color.key_pressed))
            assertEquals(color(themedContext, R.color.mist), color(themedContext, R.color.suggestion_pressed))
            assertEquals(color(themedContext, R.color.divider), color(themedContext, R.color.key_outline))
            assertEquals(color(themedContext, R.color.divider), color(themedContext, R.color.suggestion_outline))
            assertEquals(color(themedContext, R.color.pen_blue), color(themedContext, R.color.primary))
            assertEquals(color(themedContext, R.color.pen_blue), color(themedContext, R.color.signal_blue))
            assertEquals(color(themedContext, R.color.pen_blue), color(themedContext, R.color.private_teal))
            assertEquals(color(themedContext, R.color.on_accent), color(themedContext, R.color.on_action))
            assertEquals(color(themedContext, R.color.paper), color(themedContext, R.color.settings_surface))
            assertEquals(
                color(themedContext, R.color.elevated_surface),
                color(themedContext, R.color.settings_header_surface),
            )
        }
    }

    private fun Context.withNightMode(mode: Int): Context {
        val configuration = Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
        }
        return createConfigurationContext(configuration)
    }

    private fun assertPalette(themedContext: Context, expected: Map<Int, String>) {
        expected.forEach { (resource, hex) ->
            assertEquals(hex, color(themedContext, resource).toHexRgb())
        }
    }

    private fun assertContrast(themedContext: Context, foreground: Int, background: Int) {
        assertTrue(
            "${color(themedContext, foreground).toHexRgb()} on " +
                "${color(themedContext, background).toHexRgb()} must meet 4.5:1",
            ColorUtils.calculateContrast(
                color(themedContext, foreground),
                color(themedContext, background),
            ) >= 4.5,
        )
    }

    private fun color(themedContext: Context, resource: Int): Int =
        ContextCompat.getColor(themedContext, resource)

    private fun Int.toHexRgb(): String = String.format("#%06X", this and 0xFFFFFF)
}
