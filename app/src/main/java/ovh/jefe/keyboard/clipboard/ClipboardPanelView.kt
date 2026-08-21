package ovh.jefe.keyboard.clipboard

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt
import ovh.jefe.keyboard.R

internal class ClipboardPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var onBack: (() -> Unit)? = null
    var onEnable: (() -> Unit)? = null
    var onClear: (() -> Unit)? = null
    var onPaste: ((String) -> Unit)? = null
    var onPin: ((String, Boolean) -> Unit)? = null
    var onDelete: ((String) -> Unit)? = null

    private val adapter = TileAdapter()
    private val title = TextView(context).apply {
        text = context.getString(R.string.clipboard_history_title)
        textSize = 18f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ContextCompat.getColor(context, R.color.ink))
        gravity = Gravity.CENTER_VERTICAL
    }
    private val status = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(ContextCompat.getColor(context, R.color.secondary_text))
        textSize = 15f
        setPadding(dp(20), dp(20), dp(20), dp(20))
    }
    private val recycler = RecyclerView(context).apply {
        layoutManager = GridLayoutManager(context, 2)
        adapter = this@ClipboardPanelView.adapter
        setPadding(dp(8), dp(4), dp(8), dp(8))
        clipToPadding = false
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.paper))
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
                addView(action("‹", R.string.clipboard_back) { onBack?.invoke() }, LayoutParams(dp(48), dp(48)))
                addView(title, LayoutParams(0, dp(48), 1f))
                addView(action("Effacer", R.string.clipboard_clear_all) { onClear?.invoke() }, LayoutParams(dp(88), dp(48)))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(48)),
        )
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(recycler, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun render(state: ClipboardPanelUiState) {
        when (state) {
            ClipboardPanelUiState.Disabled -> {
                status.text = context.getString(R.string.clipboard_enable_action)
                status.isClickable = true
                status.isFocusable = true
                status.minimumHeight = dp(48)
                status.setOnClickListener { onEnable?.invoke() }
                recycler.visibility = GONE
                adapter.submit(emptyList())
            }
            ClipboardPanelUiState.Loading -> showMessage(R.string.clipboard_loading)
            ClipboardPanelUiState.Empty -> showMessage(R.string.clipboard_empty)
            ClipboardPanelUiState.Error -> showMessage(R.string.clipboard_error)
            is ClipboardPanelUiState.Ready -> {
                status.text = context.resources.getQuantityString(
                    R.plurals.clipboard_item_count,
                    state.tiles.size,
                    state.tiles.size,
                )
                status.isClickable = false
                status.setOnClickListener(null)
                recycler.visibility = VISIBLE
                adapter.submit(state.tiles)
            }
        }
    }

    internal fun touchControls(): List<View> = buildList {
        fun collect(view: View) {
            if (view.isClickable) add(view)
            if (view is ViewGroup) view.children.forEach(::collect)
        }
        collect(this@ClipboardPanelView)
    }

    private fun showMessage(text: Int) {
        status.setText(text)
        status.isClickable = false
        status.setOnClickListener(null)
        recycler.visibility = GONE
        adapter.submit(emptyList())
    }

    private fun action(label: String, description: Int, click: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        contentDescription = context.getString(description)
        minimumWidth = dp(44)
        minimumHeight = dp(44)
        setTextColor(ContextCompat.getColor(context, R.color.pen_blue))
        setBackgroundResource(R.drawable.bg_rail_control)
        setOnClickListener { click() }
    }

    private inner class TileAdapter : RecyclerView.Adapter<TileHolder>() {
        private var tiles: List<ClipboardTileUi> = emptyList()
        fun submit(value: List<ClipboardTileUi>) {
            tiles = value
            notifyDataSetChanged()
        }
        override fun getItemCount() = tiles.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TileHolder(tileView())
        override fun onBindViewHolder(holder: TileHolder, position: Int) = holder.bind(tiles[position])
    }

    private inner class TileHolder(private val container: LinearLayout) : RecyclerView.ViewHolder(container) {
        private val heading = container.getChildAt(0) as TextView
        private val detail = container.getChildAt(1) as TextView
        private val actions = container.getChildAt(2) as LinearLayout
        fun bind(tile: ClipboardTileUi) {
            heading.text = tile.title
            detail.text = tile.detail
            container.contentDescription = if (tile.isSensitive) "Contenu sensible · ${tile.detail}" else "${tile.title} · ${tile.detail}"
            container.setOnClickListener { onPaste?.invoke(tile.id) }
            (actions.getChildAt(0) as Button).apply {
                text = if (tile.isPinned) "Désépingler" else "Épingler"
                setOnClickListener { onPin?.invoke(tile.id, !tile.isPinned) }
            }
            (actions.getChildAt(1) as Button).setOnClickListener { onDelete?.invoke(tile.id) }
        }
    }

    private fun tileView() = LinearLayout(context).apply {
        orientation = VERTICAL
        minimumHeight = dp(124)
        setPadding(dp(12), dp(10), dp(12), dp(8))
        setBackgroundResource(R.drawable.bg_clipboard_tile)
        isClickable = true
        isFocusable = true
        addView(TextView(context).apply {
            maxLines = 2
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.ink))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(TextView(context).apply {
            maxLines = 1
            setTextColor(ContextCompat.getColor(context, R.color.secondary_text))
        })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(action("Épingler", R.string.clipboard_pin) {}, LayoutParams(0, dp(44), 1f))
            addView(action("Supprimer", R.string.clipboard_delete) {}, LayoutParams(0, dp(44), 1f))
        })
        layoutParams = RecyclerView.LayoutParams(LayoutParams.MATCH_PARENT, dp(136)).apply {
            setMargins(dp(4), dp(4), dp(4), dp(4))
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
