package ovh.jefe.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat

/** French QWERTY keyboard with a compact, private-service visual identity. */
open class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {
    var onKeyChar: ((String) -> Unit)? = null
    var onKeyDelete: (() -> Unit)? = null
    var onKeyEnter: (() -> Unit)? = null
    var onKeySpace: (() -> Unit)? = null
    var onMicClick: (() -> Unit)? = null
    var onTranslateClick: (() -> Unit)? = null
    var onSuggestionClick: ((String) -> Unit)? = null

    var suggestions: List<String> = emptyList()
        set(value) {
            field = value.take(SUGGESTION_COUNT)
            invalidate()
        }

    var isShifted = false
        set(value) {
            field = value
            invalidate()
        }

    var isRecording = false
        set(value) {
            field = value
            invalidate()
        }

    var symbolMode = false
        set(value) {
            if (field == value) return
            field = value
            recomputeLayout()
        }

    var enterAction: Int = EditorInfo.IME_ACTION_UNSPECIFIED
        set(value) {
            if (field == value) return
            field = value
            recomputeLayout()
        }

    enum class KeyAction {
        CHAR,
        DELETE,
        ENTER,
        SHIFT,
        SPACE,
        MIC,
        TRANSLATE,
        SUGGESTION,
        SYMBOLS_TOGGLE,
    }

    enum class IconType { MIC, TRANSLATE }

    data class KeyDef(
        val action: KeyAction,
        val label: String = "",
        val char: Char? = null,
        val weight: Float = 1f,
        val iconType: IconType? = null,
        val isSpecial: Boolean = false,
    )

    internal data class RenderedControl(
        val action: KeyAction,
        val label: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val hasIcon: Boolean,
        val textSizePx: Float,
        val backgroundColor: Int,
        val foregroundColor: Int,
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val height: Float get() = bottom - top
    }

    internal data class RenderedAccent(
        val label: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    private val accentMap = mapOf(
        'a' to listOf('à', 'â', 'æ'),
        'e' to listOf('é', 'è', 'ê', 'ë'),
        'i' to listOf('î', 'ï'),
        'o' to listOf('ô', 'œ'),
        'u' to listOf('ù', 'û', 'ü'),
        'c' to listOf('ç'),
        'n' to listOf('ñ'),
    )

    private val numberRow = "1234567890".map { KeyDef(KeyAction.CHAR, it.toString(), it) }
    private val letterRow1 = "qwertyuiop".map { KeyDef(KeyAction.CHAR, it.toString(), it) }
    private val letterRow2 = "asdfghjkl".map { KeyDef(KeyAction.CHAR, it.toString(), it) }
    private val letterRow3 = listOf(
        KeyDef(KeyAction.SHIFT, "⇧", weight = 1.5f, isSpecial = true),
        *"zxcvbnm".map { KeyDef(KeyAction.CHAR, it.toString(), it) }.toTypedArray(),
        KeyDef(KeyAction.DELETE, "⌫", weight = 1.5f, isSpecial = true),
    )
    private val symbolRow1 = "?!@#$%&*()".map { KeyDef(KeyAction.CHAR, it.toString(), it) }
    private val symbolRow2 = "-_=+[]{}/".map { KeyDef(KeyAction.CHAR, it.toString(), it) }
    private val symbolRow3 = listOf(
        KeyDef(KeyAction.SHIFT, "ABC", weight = 1.5f, isSpecial = true),
        *listOf(':', ';', '"', '\'', '<', '>', '\\', '|').map {
            KeyDef(KeyAction.CHAR, it.toString(), it)
        }.toTypedArray(),
        KeyDef(KeyAction.DELETE, "⌫", weight = 1.5f, isSpecial = true),
    )

    private fun buildBottomRow(): List<KeyDef> = listOf(
        KeyDef(KeyAction.MIC, iconType = IconType.MIC, weight = 1.2f, isSpecial = true),
        KeyDef(KeyAction.TRANSLATE, iconType = IconType.TRANSLATE, weight = 1.2f, isSpecial = true),
        KeyDef(KeyAction.SYMBOLS_TOGGLE, if (symbolMode) "ABC" else "123", isSpecial = true),
        KeyDef(KeyAction.CHAR, ",", ',', weight = 0.8f),
        KeyDef(KeyAction.SPACE, context.getString(R.string.keyboard_space), weight = 3.2f, isSpecial = true),
        KeyDef(KeyAction.CHAR, ".", '.', weight = 0.8f),
        KeyDef(KeyAction.ENTER, enterLabel(), weight = 1.8f, isSpecial = true),
    )

    private fun buildRows(): List<List<KeyDef>> = listOf(
        numberRow,
        if (symbolMode) symbolRow1 else letterRow1,
        if (symbolMode) symbolRow2 else letterRow2,
        if (symbolMode) symbolRow3 else letterRow3,
        buildBottomRow(),
    )

    private fun enterLabel(): String = context.getString(
        when (enterAction) {
            EditorInfo.IME_ACTION_GO -> R.string.keyboard_enter_go
            EditorInfo.IME_ACTION_SEARCH -> R.string.keyboard_enter_search
            EditorInfo.IME_ACTION_SEND -> R.string.keyboard_enter_send
            EditorInfo.IME_ACTION_PREVIOUS -> R.string.keyboard_enter_previous
            EditorInfo.IME_ACTION_NEXT -> R.string.keyboard_enter_next
            EditorInfo.IME_ACTION_DONE -> R.string.keyboard_enter_done
            else -> R.string.keyboard_enter_default
        },
    )

    private val displayMetrics = resources.displayMetrics
    private val keyHeight = dp(48)
    private val suggestionHeight = dp(48)
    private val gap = dp(6)
    private val keyCorner = dp(10)
    private val capsuleCorner = dp(20)
    private val keyTextSize = sp(18)
    private val specialTextSize = sp(12)
    private val suggestionTextSize = sp(15)
    private val utilityGlyphTextSize = sp(22)
    private val keyTypeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val actionTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun paint(color: Int, style: Paint.Style = Paint.Style.FILL) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = ContextCompat.getColor(context, color)
        this.style = style
    }

    private val surfacePaint = paint(R.color.keyboard_surface)
    private val keyPaint = paint(R.color.key_bg)
    private val specialPaint = paint(R.color.key_bg_special)
    private val pressedPaint = paint(R.color.key_pressed)
    private val outlinePaint = paint(R.color.key_outline, Paint.Style.STROKE).apply { strokeWidth = dp(1) }
    private val suggestionPaint = paint(R.color.suggestion_bg)
    private val suggestionPressedPaint = paint(R.color.suggestion_pressed)
    private val suggestionOutlinePaint = paint(R.color.suggestion_outline, Paint.Style.STROKE).apply {
        strokeWidth = dp(1)
    }
    private val actionPaint = paint(R.color.signal_blue)
    private val actionPressedPaint = paint(R.color.action_pressed)
    private val microphonePaint = paint(R.color.private_teal)
    private val recordingPaint = paint(R.color.recording_red)
    private val shiftPaint = paint(R.color.signal_blue)
    private val textPaint = paint(R.color.key_text).apply {
        textSize = keyTextSize
        textAlign = Paint.Align.CENTER
        typeface = keyTypeface
    }
    private val specialTextPaint = paint(R.color.key_text).apply {
        textSize = specialTextSize
        textAlign = Paint.Align.CENTER
        typeface = actionTypeface
    }
    private val actionTextPaint = paint(R.color.on_action).apply {
        textSize = specialTextSize
        textAlign = Paint.Align.CENTER
        typeface = actionTypeface
    }
    private val utilityGlyphPaint = paint(R.color.key_text).apply {
        textSize = utilityGlyphTextSize
        textAlign = Paint.Align.CENTER
        typeface = actionTypeface
    }
    private val activeUtilityGlyphPaint = paint(R.color.on_action).apply {
        textSize = utilityGlyphTextSize
        textAlign = Paint.Align.CENTER
        typeface = actionTypeface
    }
    private val suggestionTextPaint = paint(R.color.suggestion_text).apply {
        textSize = suggestionTextSize
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val iconDefaultColor = ContextCompat.getColor(context, R.color.key_text)
    private val iconMicColor = ContextCompat.getColor(context, R.color.mic_icon)
    private val iconMicPressedColor = ContextCompat.getColor(context, R.color.mic_pressed_icon)
    private val iconRecordingColor = ContextCompat.getColor(context, R.color.on_action)
    private val micIcon = drawable(R.drawable.ic_mic)
    private val translateIcon = drawable(R.drawable.ic_translate)

    private data class ComputedKey(val rect: RectF, val def: KeyDef, val row: Int, val col: Int)

    private var computedKeys: List<ComputedKey> = emptyList()
    private var computedSuggestions: List<ComputedKey> = emptyList()
    private var pressedKey: ComputedKey? = null
    private var longPressRunnable: Runnable? = null
    private var longPressTriggered = false
    private var gestureCancelled = false
    private val longPressDelay = 400L
    private var accentPopup: List<Char>? = null
    private var accentPopupRects: List<RectF> = emptyList()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
        contentDescription = context.getString(R.string.keyboard_accessibility_label)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = suggestionHeight + gap + buildRows().size * (keyHeight + gap) + gap
        setMeasuredDimension(width, resolveSize(desiredHeight.toInt(), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed || computedKeys.isEmpty()) computeLayout()
    }

    private fun recomputeLayout() {
        if (width > 0) computeLayout() else computedKeys = emptyList()
        requestLayout()
        invalidate()
    }

    private fun computeLayout() {
        val availableWidth = width.toFloat().coerceAtLeast(0f)
        val rows = buildRows()
        val keys = mutableListOf<ComputedKey>()
        val suggestionKeys = mutableListOf<ComputedKey>()
        val suggestionWidth = (availableWidth - gap * (SUGGESTION_COUNT + 1)) / SUGGESTION_COUNT
        repeat(SUGGESTION_COUNT) { index ->
            val left = gap + index * (suggestionWidth + gap)
            suggestionKeys += ComputedKey(
                RectF(left, gap / 2f, left + suggestionWidth, gap / 2f + suggestionHeight),
                KeyDef(KeyAction.SUGGESTION),
                -1,
                index,
            )
        }
        rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            val unitWidth = (availableWidth - gap * (row.size + 1)) / totalWeight
            val y = suggestionHeight + gap + rowIndex * (keyHeight + gap)
            var x = gap
            row.forEachIndexed { columnIndex, key ->
                val width = unitWidth * key.weight
                keys += ComputedKey(RectF(x, y, x + width, y + keyHeight), key, rowIndex, columnIndex)
                x += width + gap
            }
        }
        computedKeys = keys
        computedSuggestions = suggestionKeys
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (computedKeys.isEmpty()) computeLayout()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), surfacePaint)
        computedSuggestions.forEachIndexed { index, key ->
            val background = if (key == pressedKey && !gestureCancelled) {
                suggestionPressedPaint
            } else {
                suggestionPaint
            }
            canvas.drawRoundRect(key.rect, capsuleCorner, capsuleCorner, background)
            canvas.drawRoundRect(key.rect, capsuleCorner, capsuleCorner, suggestionOutlinePaint)
            suggestions.getOrNull(index)?.let { suggestion ->
                canvas.drawText(suggestion, key.rect.centerX(), textBaseline(key.rect, suggestionTextPaint), suggestionTextPaint)
            }
        }
        computedKeys.forEach { drawKey(canvas, it, it == pressedKey && !gestureCancelled) }
        drawAccentPopup(canvas)
    }

    private fun drawKey(canvas: Canvas, key: ComputedKey, pressed: Boolean) {
        val background = backgroundPaintFor(key.def, pressed)
        canvas.drawRoundRect(key.rect, keyCorner, keyCorner, background)
        if (!pressed && key.def.action !in setOf(KeyAction.ENTER, KeyAction.MIC) &&
            !(key.def.action == KeyAction.SHIFT && isShifted)
        ) {
            canvas.drawRoundRect(key.rect, keyCorner, keyCorner, outlinePaint)
        }
        val icon = when (key.def.iconType) {
            IconType.MIC -> micIcon
            IconType.TRANSLATE -> translateIcon
            null -> null
        }
        if (icon != null) {
            drawIcon(canvas, icon, key.rect, foregroundColorFor(key.def, pressed))
            return
        }
        val label = displayLabel(key.def)
        if (label.isEmpty()) return
        val text = textPaintFor(key.def)
        canvas.drawText(label, key.rect.centerX(), textBaseline(key.rect, text), text)
    }

    private fun backgroundPaintFor(definition: KeyDef, pressed: Boolean): Paint = when {
        definition.action == KeyAction.ENTER -> if (pressed) actionPressedPaint else actionPaint
        definition.action == KeyAction.MIC && isRecording -> if (pressed) actionPressedPaint else recordingPaint
        definition.action == KeyAction.MIC -> if (pressed) pressedPaint else microphonePaint
        definition.action == KeyAction.SHIFT && isShifted -> if (pressed) actionPressedPaint else shiftPaint
        pressed -> pressedPaint
        definition.isSpecial -> specialPaint
        else -> keyPaint
    }

    private fun foregroundColorFor(definition: KeyDef, pressed: Boolean): Int = when {
        definition.iconType == IconType.MIC && isRecording -> iconRecordingColor
        definition.iconType == IconType.MIC && pressed -> iconMicPressedColor
        definition.iconType == IconType.MIC -> iconMicColor
        definition.iconType != null -> iconDefaultColor
        definition.action == KeyAction.SUGGESTION -> suggestionTextPaint.color
        else -> textPaintFor(definition).color
    }

    private fun textPaintFor(definition: KeyDef): Paint = when {
        definition.action == KeyAction.SHIFT && isShifted -> activeUtilityGlyphPaint
        definition.action == KeyAction.SHIFT || definition.action == KeyAction.DELETE -> utilityGlyphPaint
        definition.action == KeyAction.ENTER -> actionTextPaint
        definition.isSpecial -> specialTextPaint
        else -> textPaint
    }

    private fun drawAccentPopup(canvas: Canvas) {
        val accents = accentPopup ?: return
        accentPopupRects.forEachIndexed { index, rect ->
            canvas.drawRoundRect(rect, keyCorner, keyCorner, keyPaint)
            canvas.drawRoundRect(rect, keyCorner, keyCorner, outlinePaint)
            val label = if (isShifted) accents[index].uppercaseChar() else accents[index]
            canvas.drawText(label.toString(), rect.centerX(), textBaseline(rect, textPaint), textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(event.x, event.y)
            MotionEvent.ACTION_MOVE -> updateGesture(event.x, event.y)
            MotionEvent.ACTION_UP -> finishGesture(event.x, event.y)
            MotionEvent.ACTION_CANCEL -> cancelGesture()
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    private fun beginGesture(x: Float, y: Float) {
        clearLongPress()
        longPressTriggered = false
        gestureCancelled = false
        accentPopup = null
        accentPopupRects = emptyList()
        pressedKey = hitTest(x, y)
        scheduleAccentPopup()
        invalidate()
    }

    private fun updateGesture(x: Float, y: Float) {
        val pressed = pressedKey ?: return
        val insideAllowedArea = if (longPressTriggered) {
            accentGestureBounds(pressed).contains(x, y)
        } else {
            pressed.rect.contains(x, y)
        }
        if (!insideAllowedArea) cancelGesture()
    }

    private fun finishGesture(x: Float, y: Float) {
        clearLongPress()
        var succeeded = false
        if (!gestureCancelled) {
            succeeded = if (!longPressTriggered) {
                pressedKey?.takeIf { it.rect.contains(x, y) }?.let(::handleKey) == true
            } else {
                commitAccentAt(x, y)
            }
        }
        if (succeeded) notifySuccessfulInteraction()
        clearGestureState()
    }

    private fun accentGestureBounds(pressed: ComputedKey): RectF = RectF(pressed.rect).apply {
        accentPopupRects.forEach(::union)
    }

    private fun commitAccentAt(x: Float, y: Float): Boolean {
        val accents = accentPopup ?: return false
        val index = accentPopupRects.indexOfFirst { it.contains(x, y) }
        if (index < 0) return false
        val accent = accents[index]
        onKeyChar?.invoke((if (isShifted) accent.uppercaseChar() else accent).toString())
        if (isShifted) isShifted = false
        return true
    }

    private fun scheduleAccentPopup() {
        val key = pressedKey ?: return
        val char = key.def.char ?: return
        if (symbolMode) return
        val accents = accentMap[char] ?: return
        longPressRunnable = Runnable {
            if (pressedKey != key || gestureCancelled) return@Runnable
            longPressTriggered = true
            accentPopup = accents
            val popupWidth = dp(44)
            val totalWidth = accents.size * popupWidth + (accents.size - 1) * gap
            var left = key.rect.centerX() - totalWidth / 2f
            left = left.coerceIn(gap, (width - gap - totalWidth).coerceAtLeast(gap))
            val top = (key.rect.top - keyHeight - gap).coerceAtLeast(gap / 2f)
            accentPopupRects = accents.indices.map { index ->
                val optionLeft = left + index * (popupWidth + gap)
                RectF(optionLeft, top, optionLeft + popupWidth, top + keyHeight)
            }
            invalidate()
        }.also { postDelayed(it, longPressDelay) }
    }

    private fun cancelGesture() {
        gestureCancelled = true
        clearLongPress()
        pressedKey = null
        accentPopup = null
        accentPopupRects = emptyList()
        invalidate()
    }

    private fun clearGestureState() {
        pressedKey = null
        accentPopup = null
        accentPopupRects = emptyList()
        longPressTriggered = false
        gestureCancelled = false
        invalidate()
    }

    private fun clearLongPress() {
        longPressRunnable?.let(::removeCallbacks)
        longPressRunnable = null
    }

    private fun handleKey(key: ComputedKey): Boolean = when (key.def.action) {
        KeyAction.CHAR -> {
            val char = key.def.char ?: return false
            onKeyChar?.invoke((if (isShifted && !symbolMode) char.uppercaseChar() else char).toString())
            if (isShifted && !symbolMode) isShifted = false
            true
        }
        KeyAction.DELETE -> onKeyDelete.invokeIfPresent()
        KeyAction.ENTER -> onKeyEnter.invokeIfPresent()
        KeyAction.SPACE -> onKeySpace.invokeIfPresent()
        KeyAction.SHIFT -> {
            if (symbolMode) symbolMode = false else isShifted = !isShifted
            true
        }
        KeyAction.MIC -> onMicClick.invokeIfPresent()
        KeyAction.TRANSLATE -> onTranslateClick.invokeIfPresent()
        KeyAction.SYMBOLS_TOGGLE -> {
            symbolMode = !symbolMode
            true
        }
        KeyAction.SUGGESTION -> suggestions.getOrNull(key.col)?.let { suggestion ->
            onSuggestionClick?.invoke(suggestion)
            true
        } ?: false
    }

    private fun (() -> Unit)?.invokeIfPresent(): Boolean {
        this?.invoke()
        return true
    }

    private fun notifySuccessfulInteraction() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        performClick()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        cancelGesture()
        super.onDetachedFromWindow()
    }

    private fun hitTest(x: Float, y: Float): ComputedKey? {
        computedSuggestions.firstOrNull { it.rect.contains(x, y) }?.let { suggestion ->
            return if (suggestion.col in suggestions.indices) suggestion else null
        }
        return computedKeys.firstOrNull { it.rect.contains(x, y) }
    }

    private fun displayLabel(definition: KeyDef): String =
        if (isShifted && definition.char != null && !symbolMode) {
            definition.char.uppercaseChar().toString()
        } else {
            definition.label
        }

    internal fun renderedKeys(): List<RenderedControl> {
        if (computedKeys.isEmpty() && width > 0) computeLayout()
        return computedKeys.map { it.toRenderedControl(displayLabel(it.def)) }
    }

    internal fun renderedSuggestions(): List<RenderedControl> {
        if (computedSuggestions.isEmpty() && width > 0) computeLayout()
        return computedSuggestions.mapIndexed { index, key ->
            key.toRenderedControl(suggestions.getOrNull(index).orEmpty())
        }
    }

    internal fun renderedAccentOptions(): List<RenderedAccent> {
        val accents = accentPopup ?: return emptyList()
        return accentPopupRects.mapIndexed { index, rect ->
            RenderedAccent(
                label = (if (isShifted) accents[index].uppercaseChar() else accents[index]).toString(),
                left = rect.left,
                top = rect.top,
                right = rect.right,
                bottom = rect.bottom,
            )
        }
    }

    private fun ComputedKey.toRenderedControl(label: String): RenderedControl {
        val pressed = this == pressedKey && !gestureCancelled
        return RenderedControl(
            action = def.action,
            label = label,
            left = rect.left,
            top = rect.top,
            right = rect.right,
            bottom = rect.bottom,
            hasIcon = def.iconType != null,
            textSizePx = when {
                def.iconType != null || label.isEmpty() -> 0f
                def.action == KeyAction.SUGGESTION -> suggestionTextPaint.textSize
                else -> textPaintFor(def).textSize
            },
            backgroundColor = if (def.action == KeyAction.SUGGESTION) {
                if (pressed) suggestionPressedPaint.color else suggestionPaint.color
            } else {
                backgroundPaintFor(def, pressed).color
            },
            foregroundColor = foregroundColorFor(def, pressed),
        )
    }

    private fun textBaseline(rect: RectF, paint: Paint): Float =
        rect.centerY() - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f

    private fun drawIcon(canvas: Canvas, drawable: Drawable, rect: RectF, color: Int) {
        DrawableCompat.setTint(drawable, color)
        val size = (keyHeight * 0.44f).toInt()
        val left = rect.centerX().toInt() - size / 2
        val top = rect.centerY().toInt() - size / 2
        drawable.setBounds(left, top, left + size, top + size)
        drawable.draw(canvas)
    }

    private fun drawable(resource: Int): Drawable =
        DrawableCompat.wrap(requireNotNull(ContextCompat.getDrawable(context, resource)).mutate())

    private fun dp(value: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), displayMetrics)

    private fun sp(value: Int): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value.toFloat(), displayMetrics)

    private companion object {
        const val SUGGESTION_COUNT = 3
    }
}
