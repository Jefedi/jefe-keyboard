package ovh.jefe.keyboard.clipboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class ClipboardPanelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `sensitive tile is generic and never renders payload`() {
        val view = ClipboardPanelView(context)
        val secret = "SENTINEL-password"

        view.render(
            ClipboardPanelUiState.Ready(
                listOf(ClipboardTileUi("id", "Contenu sensible", "Texte · 18 o · maintenant", true, true)),
            ),
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 640)

        val visible = allText(view)
        assertTrue(visible.any { it.contains("Contenu sensible") })
        assertFalse(visible.any { it.contains(secret) })
        assertTrue(view.touchControls().all { it.minimumHeight >= dp(44) || it.height >= dp(44) })
    }

    @Test
    fun `empty and disabled states explain the next action`() {
        val view = ClipboardPanelView(context)
        view.render(ClipboardPanelUiState.Disabled)
        assertTrue(allText(view).any { it.contains("Activer") })

        view.render(ClipboardPanelUiState.Empty)
        assertTrue(allText(view).any { it.contains("Copiez") })
    }

    @Test
    fun `panel follows system light and dark colors with readable text`() {
        listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES).forEach { night ->
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
            }
            val themed = context.createConfigurationContext(configuration)
            val surface = ContextCompat.getColor(themed, ovh.jefe.keyboard.R.color.paper)
            val text = ContextCompat.getColor(themed, ovh.jefe.keyboard.R.color.ink)
            assertTrue(ColorUtils.calculateContrast(text, surface) >= 4.5)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "notnight")
    fun `render light clipboard panel`() = renderPanel("clipboard-panel-light.png")

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "night")
    fun `render dark clipboard panel`() = renderPanel("clipboard-panel-dark.png")

    private fun renderPanel(fileName: String) {
        val view = ClipboardPanelView(context)
        view.render(
            ClipboardPanelUiState.Ready(
                listOf(
                    ClipboardTileUi("1", "Bonjour depuis Jefe", "Texte · 18 o", true, false),
                    ClipboardTileUi("2", "Contenu sensible", "Texte · 24 o", false, true),
                    ClipboardTileUi("3", "photo-vacances.jpg", "Image · 2 Mo", false, false),
                    ClipboardTileUi("4", "3 éléments", "Groupe · 640 Ko", false, false),
                ),
            ),
        )
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(660, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 660)
        val bitmap = Bitmap.createBitmap(1080, 660, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val colors = buildSet {
            for (y in 0 until bitmap.height step 12) for (x in 0 until bitmap.width step 12) add(bitmap.getPixel(x, y))
        }
        assertTrue(colors.size > 4)
        System.getenv("VISUAL_OUTPUT_DIR")?.let(::File)?.also(File::mkdirs)?.resolve(fileName)?.let { file ->
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(file.isFile && file.length() > 0)
        }
    }

    private fun allText(view: View): List<String> {
        val own = if (view is TextView) listOf(view.text.toString()) else emptyList()
        if (view !is ViewGroup) return own
        return own + (0 until view.childCount).flatMap { allText(view.getChildAt(it)) }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
