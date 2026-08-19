package ovh.jefe.keyboard

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat

/**
 * Vue clavier QWERTY — layout par poids.
 * Toggle lettres/symboles via bouton 123/#@.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ─── Callbacks ───
    var onKeyChar: ((String) -> Unit)? = null
    var onKeyDelete: (() -> Unit)? = null
    var onKeyEnter: (() -> Unit)? = null
    var onKeySpace: (() -> Unit)? = null
    var onMicClick: (() -> Unit)? = null
    var onTranslateClick: (() -> Unit)? = null
    var onSuggestionClick: ((String) -> Unit)? = null

    var suggestions: List<String> = emptyList()
        set(value) { field = value; invalidate() }

    var isShifted = false
        set(value) { field = value; invalidate() }

    var isRecording = false
        set(value) { field = value; invalidate() }

    var symbolMode = false
        set(value) { field = value; invalidate() }

    var enterAction: Int = EditorInfo.IME_ACTION_UNSPECIFIED

    // ─── Key definition ───
    enum class KeyAction { CHAR, DELETE, ENTER, SHIFT, SPACE, MIC, TRANSLATE, SUGGESTION, SYMBOLS_TOGGLE }
    enum class IconType { MIC, TRANSLATE, DELETE, ENTER, SHIFT, SYMBOLS }

    data class KeyDef(
        val action: KeyAction,
        val label: String = "",
        val char: Char? = null,
        val weight: Float = 1f,
        val iconType: IconType? = null,
        val isSpecial: Boolean = false
    )

    // ─── Accents ───
    private val accentMap = mapOf(
        'a' to listOf('à', 'â', 'æ'),
        'e' to listOf('é', 'è', 'ê', 'ë'),
        'i' to listOf('î', 'ï'),
        'o' to listOf('ô', 'œ'),
        'u' to listOf('ù', 'û', 'ü'),
        'c' to listOf('ç'),
        'n' to listOf('ñ'),
    )

    // ─── Rows ───
    private val numberRow = listOf(
        KeyDef(KeyAction.CHAR, "1", '1'), KeyDef(KeyAction.CHAR, "2", '2'),
        KeyDef(KeyAction.CHAR, "3", '3'), KeyDef(KeyAction.CHAR, "4", '4'),
        KeyDef(KeyAction.CHAR, "5", '5'), KeyDef(KeyAction.CHAR, "6", '6'),
        KeyDef(KeyAction.CHAR, "7", '7'), KeyDef(KeyAction.CHAR, "8", '8'),
        KeyDef(KeyAction.CHAR, "9", '9'), KeyDef(KeyAction.CHAR, "0", '0'),
    )

    // ─── Letter rows ───
    private val letterRow1 = listOf(
        KeyDef(KeyAction.CHAR, "q", 'q'), KeyDef(KeyAction.CHAR, "w", 'w'),
        KeyDef(KeyAction.CHAR, "e", 'e'), KeyDef(KeyAction.CHAR, "r", 'r'),
        KeyDef(KeyAction.CHAR, "t", 't'), KeyDef(KeyAction.CHAR, "y", 'y'),
        KeyDef(KeyAction.CHAR, "u", 'u'), KeyDef(KeyAction.CHAR, "i", 'i'),
        KeyDef(KeyAction.CHAR, "o", 'o'), KeyDef(KeyAction.CHAR, "p", 'p'),
    )
    private val letterRow2 = listOf(
        KeyDef(KeyAction.CHAR, "a", 'a'), KeyDef(KeyAction.CHAR, "s", 's'),
        KeyDef(KeyAction.CHAR, "d", 'd'), KeyDef(KeyAction.CHAR, "f", 'f'),
        KeyDef(KeyAction.CHAR, "g", 'g'), KeyDef(KeyAction.CHAR, "h", 'h'),
        KeyDef(KeyAction.CHAR, "j", 'j'), KeyDef(KeyAction.CHAR, "k", 'k'),
        KeyDef(KeyAction.CHAR, "l", 'l'),
    )
    private val letterRow3 = listOf(
        KeyDef(KeyAction.SHIFT, "⇧", weight = 1.5f, iconType = IconType.SHIFT, isSpecial = true),
        KeyDef(KeyAction.CHAR, "z", 'z'), KeyDef(KeyAction.CHAR, "x", 'x'),
        KeyDef(KeyAction.CHAR, "c", 'c'), KeyDef(KeyAction.CHAR, "v", 'v'),
        KeyDef(KeyAction.CHAR, "b", 'b'), KeyDef(KeyAction.CHAR, "n", 'n'),
        KeyDef(KeyAction.CHAR, "m", 'm'),
        KeyDef(KeyAction.DELETE, "⌫", weight = 1.5f, iconType = IconType.DELETE, isSpecial = true),
    )

    // ─── Symbol rows ───
    private val symbolRow1 = listOf(
        KeyDef(KeyAction.CHAR, "?", '?'), KeyDef(KeyAction.CHAR, "!", '!'),
        KeyDef(KeyAction.CHAR, "@", '@'), KeyDef(KeyAction.CHAR, "#", '#'),
        KeyDef(KeyAction.CHAR, "$", '$'), KeyDef(KeyAction.CHAR, "%", '%'),
        KeyDef(KeyAction.CHAR, "&", '&'), KeyDef(KeyAction.CHAR, "*", '*'),
        KeyDef(KeyAction.CHAR, "(", '('), KeyDef(KeyAction.CHAR, ")", ')'),
    )
    private val symbolRow2 = listOf(
        KeyDef(KeyAction.CHAR, "-", '-'), KeyDef(KeyAction.CHAR, "_", '_'),
        KeyDef(KeyAction.CHAR, "=", '='), KeyDef(KeyAction.CHAR, "+", '+'),
        KeyDef(KeyAction.CHAR, "[", '['), KeyDef(KeyAction.CHAR, "]", ']'),
        KeyDef(KeyAction.CHAR, "{", '{'), KeyDef(KeyAction.CHAR, "}", '}'),
        KeyDef(KeyAction.CHAR, "/", '/'),
    )
    private val symbolRow3 = listOf(
        KeyDef(KeyAction.SHIFT, "ABC", weight = 1.5f, isSpecial = true),
        KeyDef(KeyAction.CHAR, ":", ':'), KeyDef(KeyAction.CHAR, ";", ';'),
        KeyDef(KeyAction.CHAR, "\"", '"'), KeyDef(KeyAction.CHAR, "'", '\''),
        KeyDef(KeyAction.CHAR, "<", '<'), KeyDef(KeyAction.CHAR, ">", '>'),
        KeyDef(KeyAction.CHAR, "\\", '\\'), KeyDef(KeyAction.CHAR, "|", '|'),
        KeyDef(KeyAction.DELETE, "⌫", weight = 1.5f, iconType = IconType.DELETE, isSpecial = true),
    )

    // ─── Row 4 (common) ───
    // Letter mode: mic(1.2) + translate(1.2) + 123(1) + ,(0.8) + space(3.2) + .(0.8) + enter(1.8) = 10
    // Symbol mode: mic(1.2) + translate(1.2) + ABC(1) + ,(0.8) + space(3.2) + .(0.8) + enter(1.8) = 10
    private fun buildRow4(): List<KeyDef> = listOf(
        KeyDef(KeyAction.MIC, "", weight = 1.2f, iconType = IconType.MIC, isSpecial = true),
        KeyDef(KeyAction.TRANSLATE, "", weight = 1.2f, iconType = IconType.TRANSLATE, isSpecial = true),
        KeyDef(KeyAction.SYMBOLS_TOGGLE, if (symbolMode) "ABC" else "123", weight = 1f, isSpecial = true),
        KeyDef(KeyAction.CHAR, ",", ',', weight = 0.8f),
        KeyDef(KeyAction.SPACE, "espace", weight = 3.2f, isSpecial = true),
        KeyDef(KeyAction.CHAR, ".", '.', weight = 0.8f),
        KeyDef(KeyAction.ENTER, enterLabel(), weight = 1.8f, iconType = IconType.ENTER, isSpecial = true),
    )

    private fun enterLabel(): String {
        return when (enterAction) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Rech"
            EditorInfo.IME_ACTION_SEND -> "Env"
            EditorInfo.IME_ACTION_NEXT -> "Suiv"
            EditorInfo.IME_ACTION_DONE -> "OK"
            else -> "↵"
        }
    }

    private fun buildRows(): List<List<KeyDef>> {
        val r1 = if (symbolMode) symbolRow1 else letterRow1
        val r2 = if (symbolMode) symbolRow2 else letterRow2
        val r3 = if (symbolMode) symbolRow3 else letterRow3
        return listOf(numberRow, r1, r2, r3, buildRow4())
    }

    // ─── Dimensions ───
    private val dm = context.resources.displayMetrics
    private val keyH = dp(44)
    private val sugH = dp(38)
    private val pad = dp(4)
    private val corner = dp(6)
    private val labelTextSize = sp(18)
    private val specialTextSize = sp(13)
    private val sugTextSize = sp(15)

    // ─── Paints ───
    private val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.key_bg) }
    private val keySpecialBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.key_bg_special) }
    private val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.key_pressed) }
    private val keyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_text); textSize = labelTextSize; textAlign = Paint.Align.CENTER
    }
    private val specialTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_text); textSize = specialTextSize; textAlign = Paint.Align.CENTER
    }
    private val sugBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.suggestion_bg) }
    private val sugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.suggestion_text); textSize = sugTextSize; textAlign = Paint.Align.CENTER
    }
    private val sugDivPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val recPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.recording_red); textSize = specialTextSize; textAlign = Paint.Align.CENTER
    }
    private val shiftDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.primary) }

    // ─── Icons ───
    private val micIcon = ContextCompat.getDrawable(context, R.drawable.ic_mic)
    private val translateIcon = ContextCompat.getDrawable(context, R.drawable.ic_translate)
    private val deleteIcon = ContextCompat.getDrawable(context, android.R.drawable.ic_input_delete)
    private val enterIcon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_send)
    private val shiftIcon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_sort_by_size)

    // ─── Computed layout ───
    private data class ComputedKey(val rect: RectF, val def: KeyDef, val row: Int, val col: Int)
    private var computedKeys: List<ComputedKey> = emptyList()
    private var computedSuggestions: List<ComputedKey> = emptyList()

    // ─── Touch state ───
    private var pressedKey: ComputedKey? = null
    private var longPressRunnable: Runnable? = null
    private var longPressTriggered = false
    private val longPressDelay = 400L
    private var accentPopup: List<Char>? = null
    private var accentPopupRects: List<RectF> = emptyList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = buildRows()
        val totalH = sugH + pad + rows.size * (keyH + pad) + pad
        setMeasuredDimension(w, totalH.toInt())
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) computeLayout()
    }

    private fun computeLayout() {
        val w = width.toFloat()
        val rows = buildRows()
        val result = mutableListOf<ComputedKey>()
        val sugResult = mutableListOf<ComputedKey>()

        // Suggestion bar
        val sugW = w / 3f
        for (i in 0..2) {
            sugResult.add(ComputedKey(RectF(i * sugW, 0f, (i + 1) * sugW, sugH), KeyDef(KeyAction.SUGGESTION), -1, i))
        }

        // Keyboard rows
        for ((rowIdx, row) in rows.withIndex()) {
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            val availW = w - pad * (row.size + 1)
            val unitW = availW / totalWeight
            val y = sugH + pad + rowIdx * (keyH + pad)
            var x = pad
            for ((colIdx, key) in row.withIndex()) {
                val kw = unitW * key.weight
                result.add(ComputedKey(RectF(x, y, x + kw, y + keyH), key, rowIdx, colIdx))
                x += kw + pad
            }
        }

        computedKeys = result
        computedSuggestions = sugResult
    }

    // ─── Draw ───
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (computedKeys.isEmpty()) computeLayout()

        // Suggestion bar
        for (i in computedSuggestions.indices) {
            val ck = computedSuggestions[i]
            canvas.drawRect(ck.rect, sugBgPaint)
            if (i < suggestions.size) {
                canvas.drawText(suggestions[i], ck.rect.centerX(), ck.rect.centerY() + sugTextSize / 3, sugTextPaint)
            }
            if (i > 0) canvas.drawLine(ck.rect.left, 5f, ck.rect.left, sugH - 5f, sugDivPaint)
        }
        if (isRecording) canvas.drawText("● REC", width / 2f, sugH - 8f, recPaint)

        // Keys
        for (ck in computedKeys) drawKey(canvas, ck, pressedKey == ck)

        // Accent popup
        if (accentPopup != null && accentPopupRects.isNotEmpty()) {
            for ((i, rect) in accentPopupRects.withIndex()) {
                canvas.drawRoundRect(rect, corner, corner, keyBgPaint)
                val c = accentPopup!![i]
                canvas.drawText(
                    (if (isShifted) c.uppercaseChar() else c).toString(),
                    rect.centerX(), rect.centerY() + labelTextSize / 3, keyTextPaint
                )
            }
        }
    }

    private fun drawKey(canvas: Canvas, ck: ComputedKey, pressed: Boolean) {
        val def = ck.def
        val bg = when {
            pressed -> keyPressedPaint
            def.action == KeyAction.MIC && isRecording -> recPaint
            def.isSpecial -> keySpecialBgPaint
            else -> keyBgPaint
        }
        canvas.drawRoundRect(ck.rect, corner, corner, bg)

        val icon = when (def.iconType) {
            IconType.MIC -> micIcon
            IconType.TRANSLATE -> translateIcon
            IconType.DELETE -> deleteIcon
            IconType.ENTER -> enterIcon
            IconType.SHIFT -> shiftIcon
            else -> null
        }

        if (icon != null) {
            val sz = (keyH * 0.45f).toInt()
            val cx = ck.rect.centerX().toInt() - sz / 2
            val cy = ck.rect.centerY().toInt() - sz / 2
            icon.setBounds(cx, cy, cx + sz, cy + sz)
            icon.draw(canvas)
            if (def.action == KeyAction.SHIFT && isShifted) {
                canvas.drawCircle(ck.rect.centerX(), ck.rect.bottom - dp(6), dp(3), shiftDotPaint)
            }
        } else if (def.label.isNotEmpty()) {
            val display = if (isShifted && def.char != null && !symbolMode) {
                def.char.uppercaseChar().toString()
            } else {
                def.label
            }
            val tp = if (def.isSpecial) specialTextPaint else keyTextPaint
            canvas.drawText(display, ck.rect.centerX(), ck.rect.centerY() + tp.textSize / 3, tp)
        }
    }

    // ─── Touch ───
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressTriggered = false
                accentPopup = null; accentPopupRects = emptyList()
                pressedKey = hitTest(event.x, event.y)
                invalidate()

                // Long press accents (letter mode only)
                if (pressedKey != null && pressedKey!!.def.char != null && !symbolMode) {
                    val accents = accentMap[pressedKey!!.def.char]
                    if (accents != null && accents.isNotEmpty()) {
                        val k = pressedKey!!
                        longPressRunnable = Runnable {
                            longPressTriggered = true
                            accentPopup = accents
                            val popupW = dp(40)
                            val totalW = accents.size * popupW + (accents.size - 1) * pad
                            var px = k.rect.centerX() - totalW / 2f
                            if (px < pad) px = pad
                            if (px + totalW > width - pad) px = width - pad - totalW
                            val py = k.rect.top - keyH - pad
                            accentPopupRects = accents.mapIndexed { i, _ ->
                                RectF(px + i * (popupW + pad), py, px + i * (popupW + pad) + popupW, py + keyH)
                            }
                            invalidate()
                        }
                        postDelayed(longPressRunnable!!, longPressDelay)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                longPressRunnable?.let { removeCallbacks(it) }
                longPressRunnable = null
                if (!longPressTriggered) {
                    pressedKey?.let { handleKey(it) }
                } else if (accentPopup != null) {
                    for ((i, rect) in accentPopupRects.withIndex()) {
                        if (rect.contains(event.x, event.y)) {
                            val c = accentPopup!![i]
                            onKeyChar?.invoke((if (isShifted) c.uppercaseChar() else c).toString())
                            break
                        }
                    }
                }
                pressedKey = null; accentPopup = null; accentPopupRects = emptyList()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { removeCallbacks(it) }
                longPressRunnable = null
                pressedKey = null; accentPopup = null; accentPopupRects = emptyList()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun hitTest(x: Float, y: Float): ComputedKey? {
        for (ck in computedSuggestions) {
            if (ck.rect.contains(x, y)) { if (ck.col < suggestions.size) return ck; return null }
        }
        for (ck in computedKeys) { if (ck.rect.contains(x, y)) return ck }
        return null
    }

    private fun handleKey(ck: ComputedKey) {
        when (ck.def.action) {
            KeyAction.CHAR -> {
                val c = ck.def.char ?: return
                val display = if (isShifted && !symbolMode) c.uppercaseChar() else c
                onKeyChar?.invoke(display.toString())
                if (isShifted && !symbolMode) isShifted = false
            }
            KeyAction.DELETE -> onKeyDelete?.invoke()
            KeyAction.ENTER -> onKeyEnter?.invoke()
            KeyAction.SPACE -> onKeySpace?.invoke()
            KeyAction.SHIFT -> {
                if (symbolMode) { symbolMode = false; computeLayout() }
                else isShifted = !isShifted
            }
            KeyAction.MIC -> onMicClick?.invoke()
            KeyAction.TRANSLATE -> onTranslateClick?.invoke()
            KeyAction.SYMBOLS_TOGGLE -> { symbolMode = !symbolMode; computeLayout() }
            KeyAction.SUGGESTION -> { if (ck.col in suggestions.indices) onSuggestionClick?.invoke(suggestions[ck.col]) }
        }
    }

    private fun dp(v: Int): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm)
    private fun sp(v: Int): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v.toFloat(), dm)
}