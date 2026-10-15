package dev.pocket.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ctrl and Shift for a soft keyboard. Tap once to apply to the next key or
 * typed letter; tap twice to lock until tapped again.
 */
@Stable
class KeyModifiers {
    var ctrl by mutableStateOf(Latch.Off)
    var shift by mutableStateOf(Latch.Off)

    enum class Latch { Off, Once, Locked }

    fun tap(latch: Latch): Latch = when (latch) {
        Latch.Off -> Latch.Once
        Latch.Once -> Latch.Locked
        Latch.Locked -> Latch.Off
    }

    /** Reads a modifier for one key press, releasing it unless it is locked. */
    fun useCtrl(): Boolean = (ctrl != Latch.Off).also { if (ctrl == Latch.Once) ctrl = Latch.Off }
    fun useShift(): Boolean = (shift != Latch.Off).also { if (shift == Latch.Once) shift = Latch.Off }
}

@Composable
fun rememberKeyModifiers(): KeyModifiers = remember { KeyModifiers() }

/**
 * Handles a change from the text field. With Ctrl armed, a typed letter
 * becomes a shortcut (A select all, C copy, X cut, V paste, Z undo, Y redo)
 * instead of being inserted.
 */
fun KeyModifiers.filterTyped(
    old: TextFieldValue,
    new: TextFieldValue,
    clipboard: ClipboardManager,
    onUndo: (() -> Unit)? = null,
    onRedo: (() -> Unit)? = null,
): TextFieldValue {
    if (ctrl == KeyModifiers.Latch.Off) return new
    val typed = TextKeys.typedChar(old, new) ?: return new
    useCtrl()
    return ctrlShortcut(old, typed, clipboard, onUndo, onRedo)
}

private fun ctrlShortcut(
    v: TextFieldValue,
    key: Char,
    clipboard: ClipboardManager,
    onUndo: (() -> Unit)?,
    onRedo: (() -> Unit)?,
): TextFieldValue = when (key.lowercaseChar()) {
    'a' -> TextKeys.selectAll(v)
    'c' -> v.also { TextKeys.selected(it).takeIf(String::isNotEmpty)?.let { s -> clipboard.setText(AnnotatedString(s)) } }
    'x' -> {
        val s = TextKeys.selected(v)
        if (s.isEmpty()) v else {
            clipboard.setText(AnnotatedString(s))
            TextKeys.insert(v, "")
        }
    }
    'v' -> clipboard.getText()?.text?.let { TextKeys.insert(v, it) } ?: v
    'z' -> v.also { onUndo?.invoke() }
    'y' -> v.also { onRedo?.invoke() }
    else -> v
}

/**
 * The extra-keys row shown above the keyboard while a text field has focus.
 * Shift extends the selection with arrows, Home and End; Ctrl jumps by word
 * with arrows and to the start or end of the text with Home and End.
 */
@Composable
fun ExtraKeysBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifiers: KeyModifiers,
    modifier: Modifier = Modifier,
    indentUnit: String = "  ",
    onEsc: (() -> Unit)? = null,
    escLabel: String = "Esc",
    onUndo: (() -> Unit)? = null,
    onRedo: (() -> Unit)? = null,
    markdownKeys: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current

    fun move(direction: TextKeys.Move) {
        val word = modifiers.useCtrl()
        val select = modifiers.useShift()
        onValueChange(TextKeys.move(value, direction, select = select, word = word))
    }

    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onEsc != null) Key(escLabel) { onEsc() }
            Key("Tab") {
                onValueChange(TextKeys.indent(value, indentUnit, outdent = modifiers.useShift()))
            }
            Key("Ctrl", latch = modifiers.ctrl) { modifiers.ctrl = modifiers.tap(modifiers.ctrl) }
            Key("Shift", latch = modifiers.shift) { modifiers.shift = modifiers.tap(modifiers.shift) }
            Key("←") { move(TextKeys.Move.Left) }
            Key("↓") { move(TextKeys.Move.Down) }
            Key("↑") { move(TextKeys.Move.Up) }
            Key("→") { move(TextKeys.Move.Right) }
            Key("Home") { move(TextKeys.Move.Home) }
            Key("End") { move(TextKeys.Move.End) }
            Key("↵", description = "New line") { onValueChange(TextKeys.newline(value)) }
            if (markdownKeys) {
                Key("```", description = "Code block") { onValueChange(TextKeys.codeBlock(value)) }
                Key("`", description = "Inline code") { onValueChange(TextKeys.wrap(value, "`", "`")) }
            }
            if (onUndo != null) Key("Undo") { onUndo() }
            if (onRedo != null) Key("Redo") { onRedo() }
            Key("Copy") {
                TextKeys.selected(value).takeIf(String::isNotEmpty)?.let { clipboard.setText(AnnotatedString(it)) }
            }
            Key("Paste") { clipboard.getText()?.text?.let { onValueChange(TextKeys.insert(value, it)) } }
            SYMBOLS.forEach { symbol ->
                Key(symbol) {
                    val pair = PAIRS[symbol]
                    onValueChange(if (pair != null) TextKeys.wrap(value, symbol, pair) else TextKeys.insert(value, symbol))
                }
            }
        }
    }
}

// Characters that sit behind long-presses or extra pages on phone keyboards.
// Openers insert their closing pair, around the selection if any.
private val SYMBOLS = listOf("/", "|", "-", "_", "=", "~", "\\", "{", "[", "(", "<", ">", ";", ":", "\"", "'", "$", "&", "*", "#")
private val PAIRS = mapOf("{" to "}", "[" to "]", "(" to ")", "\"" to "\"", "'" to "'")

@Composable
private fun Key(
    label: String,
    latch: KeyModifiers.Latch = KeyModifiers.Latch.Off,
    description: String? = null,
    onClick: () -> Unit,
) {
    val active = latch != KeyModifiers.Latch.Off
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = when (latch) {
            KeyModifiers.Latch.Off -> MaterialTheme.colorScheme.surfaceVariant
            KeyModifiers.Latch.Once -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            KeyModifiers.Latch.Locked -> MaterialTheme.colorScheme.primary
        },
        border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.height(36.dp).widthIn(min = 40.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                fontSize = 14.sp,
                color = if (latch == KeyModifiers.Latch.Locked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.then(
                    if (description != null) Modifier.semanticsDescription(description) else Modifier,
                ),
            )
        }
    }
}

private fun Modifier.semanticsDescription(text: String): Modifier =
    semantics { contentDescription = text }
