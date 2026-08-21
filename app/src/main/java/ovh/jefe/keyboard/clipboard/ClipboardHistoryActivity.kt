package ovh.jefe.keyboard.clipboard

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class ClipboardHistoryActivity : AppCompatActivity() {
    private lateinit var panel: ClipboardPanelView
    private lateinit var controller: ClipboardPanelController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        panel = ClipboardPanelView(this)
        setContentView(panel)
        val component = ClipboardComponent.get(this)
        controller = ClipboardPanelController(component.repository, component.controller, lifecycleScope)
        panel.onBack = { finish() }
        panel.onEnable = controller::enable
        panel.onClear = {
            AlertDialog.Builder(this)
                .setTitle("Tout effacer ?")
                .setMessage("Les éléments épinglés seront aussi supprimés.")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Tout effacer") { _, _ -> controller.clear() }
                .show()
        }
        panel.onPin = controller::setPinned
        panel.onDelete = controller::delete
        lifecycleScope.launch { controller.state.collectLatest(panel::render) }
    }
}
