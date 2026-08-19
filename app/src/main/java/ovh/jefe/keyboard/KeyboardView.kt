package ovh.jefe.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Vue clavier QWERTY avec:
 * - Rangée chiffres (1-0) toujours visible
 * - Layout QWERTY + accents FR via appui long
 * - Barre de suggestions en haut
 * - Boutons: mic (dictée), traduire, supprimer, entrée, shift, espace
 * - Tout dessiné sur Canvas — pas de layout XML par touche
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
    var onKeyShift: (() -> Unit)? = null
    var onMicClick: (() -> Unit)? = null
    var onTranslateClick: (() -> Unit)? = null
    var onSuggestionClick: ((String) -> Unit)? = null

    // ─── State ───
    var suggestions: List<String> = emptyList()
        set(value) { field = value; invalidate() }

    var isShifted = false
        set(value) { field = value; invalidate() }

    var isRecording = false
        set(value) { field = value; invalidate() }

    // ─── Layouts ───
    private val qwertyRows = listOf(
        "1234567890",
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm"
    )

    // Accents pour appui long (FR)
    private val accentMap = mapOf(
        'a' to listOf('à', 'â', 'æ'),
        'e' to listOf('é', 'è', 'ê', 'ë'),
        'i' to listOf('î', 'ï'),
        'o' to listOf('ô', 'œ'),
        'u' to listOf('ù', 'û', 'ü'),
        'c' to listOf('ç'),
        'n' to listOf('ñ'),
    )

    // ─── Dimensions ───
    private val keyHeight: Float
    private val suggestionHeight: Float
    private val keyPadding: Float
    private val cornerRadius: Float
    private val labelTextSize: Float
    private val specialTextSize: Float

    // ─── Paints ───
    private val keyBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keySpecialBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specialTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val suggestionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val suggestionBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val suggestionDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val recordingPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val micIcon: Drawable?
    private val translateIcon: Drawable?
    private val deleteIcon: Drawable?
    private val enterIcon: Drawable?
    private val shiftIcon: Drawable?

    // ─── Touch state ───
    private var pressedKey: KeyPos? = null
    private var longPressRunnable: Runnable? = null
    private var longPressTriggered = false
    private val longPressDelay = 400L

    private var accentPopup: List<Char>? = null
    private var accentPopupX = 0f
    private var accentPopupY = 0f

    private data class KeyPos(val row: Int, val col: Int, val type: KeyType, val char: Char? = null)
    private enum class KeyType { CHAR, DELETE, ENTER, SHIFT, SPACE, MIC, TRANSLATE, SUGGESTION }

    init {
        val dm = context.resources.displayMetrics
        keyHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 44f, dm)
        suggestionHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 38f, dm)
        keyPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, dm)
        cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, dm)
        labelTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 18f, dm)
        specialTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, dm)

        keyBgPaint.color = ContextCompat.getColor(context, R.color.key_bg)
        keySpecialBgPaint.color = ContextCompat.getColor(context, R.color.key_bg_special)
        keyPressedPaint.color = ContextCompat.getColor(context, R.color.key_pressed)
        keyTextPaint.color = ContextCompat.getColor(context, R.color.key_text)
        keyTextPaint.textSize = labelTextSize
        keyTextPaint.textAlign = Paint.Align.CENTER
        specialTextPaint.color = ContextCompat.getColor(context, R.color.key_text)
        specialTextPaint.textSize = specialTextSize
        specialTextPaint.textAlign = Paint.Align.CENTER
        suggestionTextPaint.color = ContextCompat.getColor(context, R.color.suggestion_text)
        suggestionTextPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, dm)
        suggestionTextPaint.textAlign = Paint.Align.CENTER
        suggestionBgPaint.color = ContextCompat.getColor(context, R.color.suggestion_bg)
        suggestionDividerPaint.color = 0x33000000
        recordingPaint.color = ContextCompat.getColor(context, R.color.recording_red)
        recordingPaint.textSize = specialTextSize
        recordingPaint.textAlign = Paint.Align.CENTER

        micIcon = ContextCompat.getDrawable(context, R.drawable.ic_mic)
        translateIcon = ContextCompat.getDrawable(context, R.drawable.ic_translate)
        deleteIcon = ContextCompat.getDrawable(context, android.R.drawable.ic_input_delete)
        enterIcon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_send)
        shiftIcon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_sort_by_size)
    }

    // ─── Layout computation ───
    private fun getKeyRect(row: Int, col: Int, totalCols: Int, rowOffset: Float, keyWidth: Float): RectF {
        val left = keyPadding + col * (keyWidth + keyPadding) + rowOffset
        val top = suggestionHeight + keyPadding + row * (keyHeight + keyPadding)
        return RectF(left, top, left + keyWidth, top + keyHeight)
    }

    private fun getSpecialKeyRect(row: Int, col: Int, widthRatio: Float, rowOffset: Float, keyWidth: Float): RectF {
        val w = keyWidth * widthRatio
        val left = keyPadding + col * (keyWidth + keyPadding) + rowOffset
        val top = suggestionHeight + keyPadding + row * (keyHeight + keyPadding)
        return RectF(left, top, left + w, top + keyHeight)
    }

    private fun getSuggestionRect(index: Int): RectF {
        val w = width / 3f
        return RectF(index * w, 0f, (index + 1) * w, suggestionHeight)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalHeight = (suggestionHeight + 5 * (keyHeight + keyPadding) + keyPadding).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), totalHeight)
    }

    // ─── Draw ───
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val baseKeyWidth = (w - 11 * keyPadding) / 10f

        // ─── Suggestion bar ───
        canvas.drawRect(0f, 0f, w, suggestionHeight, suggestionBgPaint)
        for (i in suggestions.indices) {
            val rect = getSuggestionRect(i)
            canvas.drawText(suggestions[i], rect.centerX(), rect.centerY() + suggestionTextPaint.textSize / 3, suggestionTextPaint)
            if (i > 0) {
                canvas.drawLine(rect.left, 5f, rect.left, suggestionHeight - 5f, suggestionDividerPaint)
            }
        }
        if (isRecording) {
            canvas.drawText("● Enregistrement…", w / 2, suggestionHeight - 8f, recordingPaint)
        }

        // ─── Row 0: Numbers ───
        for (col in 0..9) {
            val rect = getKeyRect(0, col, 10, 0f, baseKeyWidth)
            drawKey(canvas, rect, (col + 1).toString().let { if (col == 9) "0" else it }, pressedKey?.row == 0 && pressedKey?.col == col)
        }

        // ─── Row 1: QWERTYUIOP ───
        for (col in 0..9) {
            val rect = getKeyRect(1, col, 10, 0f, baseKeyWidth)
            val c = qwertyRows[1][col]
            val display = if (isShifted) c.uppercaseChar().toString() else c.toString()
            drawKey(canvas, rect, display, pressedKey?.row == 1 && pressedKey?.col == col)
        }

        // ─── Row 2: ASDFGHJKL (slightly offset) ───
        val row2Offset = baseKeyWidth * 0.3f
        for (col in 0..8) {
            val rect = getKeyRect(2, col, 9, row2Offset, baseKeyWidth)
            val c = qwertyRows[2][col]
            val display = if (isShifted) c.uppercaseChar().toString() else c.toString()
            drawKey(canvas, rect, display, pressedKey?.row == 2 && pressedKey?.col == col)
        }

        // ─── Row 3: ZXCVBNM + special keys ───
        val row3Y = suggestionHeight + keyPadding + 3 * (keyHeight + keyPadding)
        val shiftWidth = baseKeyWidth * 1.5f
        val deleteWidth = baseKeyWidth * 1.5f
        val charCols = 7
        val charAreaWidth = 7 * baseKeyWidth + 6 * keyPadding
        val row3Total = shiftWidth + keyPadding + charAreaWidth + keyPadding + deleteWidth + keyPadding + baseKeyWidth * 2 + keyPadding
        val row3Start = (w - row3Total) / 2f + keyPadding

        // Shift key
        var x = row3Start
        val shiftRect = RectF(x, row3Y, x + shiftWidth, row3Y + keyHeight)
        drawSpecialKey(canvas, shiftRect, shiftIcon, pressedKey?.type == KeyType.SHIFT)
        x += shiftWidth + keyPadding

        // ZXCVBNM
        for (col in 0..6) {
            val c = qwertyRows[3][col]
            val display = if (isShifted) c.uppercaseChar().toString() else c.toString()
            val rect = RectF(x, row3Y, x + baseKeyWidth, row3Y + keyHeight)
            drawKey(canvas, rect, display, pressedKey?.row == 3 && pressedKey?.col == col)
            x += baseKeyWidth + keyPadding
        }

        // Delete key
        val delRect = RectF(x, row3Y, x + deleteWidth, row3Y + keyHeight)
        drawSpecialKey(canvas, delRect, deleteIcon, pressedKey?.type == KeyType.DELETE)
        x += deleteWidth + keyPadding

        // ─── Row 4: Mic, Translate, Comma, Space, Period, Enter ───
        val row4Y = suggestionHeight + keyPadding + 4 * (keyHeight + keyPadding)
        val micW = baseKeyWidth * 1.3f
        val translateW = baseKeyWidth * 1.3f
        val commaW = baseKeyWidth * 0.8f
        val periodW = baseKeyWidth * 0.8f
        val spaceW = baseKeyWidth * 3.5f
        val enterW = baseKeyWidth * 1.8f
        val row4Total = micW + translateW + commaW + spaceW + periodW + enterW + 6 * keyPadding
        var x4 = (w - row4Total) / 2f

        // Mic
        val micRect = RectF(x4, row4Y, x4 + micW, row4Y + keyHeight)
        val micBg = if (isRecording) recordingPaint else keySpecialBgPaint
        drawSpecialKey(canvas, micRect, micIcon, pressedKey?.type == KeyType.MIC, micBg)
        x4 += micW + keyPadding

        // Translate
        val trRect = RectF(x4, row4Y, x4 + translateW, row4Y + keyHeight)
        drawSpecialKey(canvas, trRect, translateIcon, pressedKey?.type == KeyType.TRANSLATE)
        x4 += translateW + keyPadding

        // Comma
        val commaRect = RectF(x4, row4Y, x4 + commaW, row4Y + keyHeight)
        drawKey(canvas, commaRect, ",", pressedKey?.row == 4 && pressedKey?.col == 0)
        x4 += commaW + keyPadding

        // Space
        val spaceRect = RectF(x4, row4Y, x4 + spaceW, row4Y + keyHeight)
        drawSpecialKey(canvas, spaceRect, null, pressedKey?.type == KeyType.SPACE, keySpecialBgPaint, "espace")
        x4 += spaceW + keyPadding

        // Period
        val periodRect = RectF(x4, row4Y, x4 + periodW, row4Y + keyHeight)
        drawKey(canvas, periodRect, ".", pressedKey?.row == 4 && pressedKey?.col == 1)
        x4 += periodW + keyPadding

        // Enter
        val enterRect = RectF(x4, row4Y, x4 + enterW, row4Y + keyHeight)
        drawSpecialKey(canvas, enterRect, enterIcon, pressedKey?.type == KeyType.ENTER)
    }

    private fun drawKey(canvas: Canvas, rect: RectF, label: String, pressed: Boolean) {
        val paint = if (pressed) keyPressedPaint else keyBgPaint
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        canvas.drawText(label, rect.centerX(), rect.centerY() + labelTextSize / 3, keyTextPaint)
    }

    private fun drawSpecialKey(canvas: Canvas, rect: RectF, icon: Drawable?, pressed: Boolean, bgPaint: Paint? = null, label: String? = null) {
        val paint = if (pressed) keyPressedPaint else (bgPaint ?: keySpecialBgPaint)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        if (icon != null) {
            val iconSize = (keyHeight * 0.5f).toInt()
            val cx = rect.centerX().toInt() - iconSize / 2
            val cy = rect.centerY().toInt() - iconSize / 2
            icon.setBounds(cx, cy, cx + iconSize, cy + iconSize)
            icon.draw(canvas)
        } else if (label != null) {
            canvas.drawText(label, rect.centerX(), rect.centerY() + specialTextSize / 3, specialTextPaint)
        }
    }

    // ─── Touch handling ───
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressTriggered = false
                val key = hitTest(event.x, event.y)
                if (key != null) {
                    pressedKey = key
                    invalidate()

                    // Long press for accents on char keys
                    if (key.type == KeyType.CHAR && key.char != null) {
                        val accents = accentMap[key.char]
                        if (accents != null && accents.isNotEmpty()) {
                            longPressRunnable = Runnable {
                                longPressTriggered = true
                                accentPopup = accents
                                accentPopupX = event.x
                                accentPopupY = event.y - keyHeight
                                invalidate()
                            }
                            postDelayed(longPressRunnable!!, longPressDelay)
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                longPressRunnable?.let { removeCallbacks(it) }
                longPressRunnable = null

                val key = pressedKey
                if (key != null && !longPressTriggered) {
                    handleKeyPress(key)
                }
                pressedKey = null

                // If accent popup was shown, handle accent selection
                if (longPressTriggered && accentPopup != null) {
                    // Find which accent was tapped
                    val accents = accentPopup!!
                    val popupY = accentPopupY
                    val popupWidth = baseKeyWidthForAccents()
                    for (i in accents.indices) {
                        val ax = accentPopupX - (accents.size * popupWidth) / 2f + i * popupWidth
                        if (event.x >= ax && event.x < ax + popupWidth && event.y >= popupY && event.y < popupY + keyHeight) {
                            val displayChar = if (isShifted) accents[i].uppercaseChar() else accents[i]
                            onKeyChar?.invoke(displayChar.toString())
                            break
                        }
                    }
                    accentPopup = null
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { removeCallbacks(it) }
                longPressRunnable = null
                pressedKey = null
                accentPopup = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun baseKeyWidthForAccents() = (width - 11 * keyPadding) / 10f

    private fun hitTest(x: Float, y: Float): KeyPos? {
        val w = width.toFloat()
        val baseKeyWidth = (w - 11 * keyPadding) / 10f

        // Suggestions
        if (y < suggestionHeight) {
            val idx = (x / (w / 3f)).toInt()
            if (idx in suggestions.indices) {
                return KeyPos(-1, idx, KeyType.SUGGESTION)
            }
            return null
        }

        // Row 0: numbers
        for (col in 0..9) {
            val rect = getKeyRect(0, col, 10, 0f, baseKeyWidth)
            if (rect.contains(x, y)) return KeyPos(0, col, KeyType.CHAR, (col + 1).toString().let { if (col == 9) '0' else it[0] })
        }

        // Row 1: qwertyuiop
        for (col in 0..9) {
            val rect = getKeyRect(1, col, 10, 0f, baseKeyWidth)
            if (rect.contains(x, y)) return KeyPos(1, col, KeyType.CHAR, qwertyRows[1][col])
        }

        // Row 2: asdfghjkl
        val row2Offset = baseKeyWidth * 0.3f
        for (col in 0..8) {
            val rect = getKeyRect(2, col, 9, row2Offset, baseKeyWidth)
            if (rect.contains(x, y)) return KeyPos(2, col, KeyType.CHAR, qwertyRows[2][col])
        }

        // Row 3: shift + zxcvbnm + delete
        val row3Y = suggestionHeight + keyPadding + 3 * (keyHeight + keyPadding)
        val shiftWidth = baseKeyWidth * 1.5f
        val deleteWidth = baseKeyWidth * 1.5f
        val charAreaWidth = 7 * baseKeyWidth + 6 * keyPadding
        val row3Total = shiftWidth + keyPadding + charAreaWidth + keyPadding + deleteWidth + 2 * keyPadding
        val row3Start = (w - row3Total) / 2f + keyPadding

        var xCursor = row3Start
        if (y >= row3Y && y < row3Y + keyHeight) {
            // Shift
            if (x >= xCursor && x < xCursor + shiftWidth) return KeyPos(3, -1, KeyType.SHIFT)
            xCursor += shiftWidth + keyPadding
            // Chars
            for (col in 0..6) {
                if (x >= xCursor && x < xCursor + baseKeyWidth) return KeyPos(3, col, KeyType.CHAR, qwertyRows[3][col])
                xCursor += baseKeyWidth + keyPadding
            }
            // Delete
            if (x >= xCursor && x < xCursor + deleteWidth) return KeyPos(3, -2, KeyType.DELETE)
        }

        // Row 4: mic, translate, comma, space, period, enter
        val row4Y = suggestionHeight + keyPadding + 4 * (keyHeight + keyPadding)
        if (y >= row4Y && y < row4Y + keyHeight) {
            val micW = baseKeyWidth * 1.3f
            val translateW = baseKeyWidth * 1.3f
            val commaW = baseKeyWidth * 0.8f
            val periodW = baseKeyWidth * 0.8f
            val spaceW = baseKeyWidth * 3.5f
            val enterW = baseKeyWidth * 1.8f
            val row4Total = micW + translateW + commaW + spaceW + periodW + enterW + 6 * keyPadding
            var x4 = (w - row4Total) / 2f

            if (x >= x4 && x < x4 + micW) return KeyPos(4, -3, KeyType.MIC)
            x4 += micW + keyPadding
            if (x >= x4 && x < x4 + translateW) return KeyPos(4, -4, KeyType.TRANSLATE)
            x4 += translateW + keyPadding
            if (x >= x4 && x < x4 + commaW) return KeyPos(4, 0, KeyType.CHAR, ',')
            x4 += commaW + keyPadding
            if (x >= x4 && x < x4 + spaceW) return KeyPos(4, -5, KeyType.SPACE)
            x4 += spaceW + keyPadding
            if (x >= x4 && x < x4 + periodW) return KeyPos(4, 1, KeyType.CHAR, '.')
            x4 += periodW + keyPadding
            if (x >= x4 && x < x4 + enterW) return KeyPos(4, -6, KeyType.ENTER)
        }

        return null
    }

    private fun handleKeyPress(key: KeyPos) {
        when (key.type) {
            KeyType.CHAR -> {
                val c = key.char ?: return
                val display = if (isShifted) c.uppercaseChar() else c
                onKeyChar?.invoke(display.toString())
                if (isShifted) {
                    isShifted = false
                }
            }
            KeyType.DELETE -> onKeyDelete?.invoke()
            KeyType.ENTER -> onKeyEnter?.invoke()
            KeyType.SPACE -> onKeySpace?.invoke()
            KeyType.SHIFT -> {
                isShifted = !isShifted
            }
            KeyType.MIC -> onMicClick?.invoke()
            KeyType.TRANSLATE -> onTranslateClick?.invoke()
            KeyType.SUGGESTION -> {
                if (key.col in suggestions.indices) {
                    onSuggestionClick?.invoke(suggestions[key.col])
                }
            }
        }
    }
}