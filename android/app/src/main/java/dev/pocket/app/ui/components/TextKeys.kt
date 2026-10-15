package dev.pocket.app.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Keyboard actions that phone keyboards lack, applied to a text field value.
 * A selection is anchored at `selection.start` with the caret at
 * `selection.end`, so Shift+arrow can grow it in either direction.
 */
object TextKeys {
    enum class Move { Left, Right, Up, Down, Home, End }

    fun insert(v: TextFieldValue, text: String): TextFieldValue {
        val (from, to) = ordered(v)
        val next = v.text.substring(0, from) + text + v.text.substring(to)
        return TextFieldValue(next, TextRange(from + text.length))
    }

    /** Newline that keeps the current line's indentation. */
    fun newline(v: TextFieldValue): TextFieldValue {
        val (from, _) = ordered(v)
        val lineStart = lineStart(v.text, from)
        val indent = v.text.substring(lineStart, from).takeWhile { it == ' ' || it == '\t' }
        return insert(v, "\n" + indent)
    }

    /** Tab indents the selected lines (or inserts at the caret); Shift+Tab outdents. */
    fun indent(v: TextFieldValue, unit: String, outdent: Boolean): TextFieldValue {
        val (from, to) = ordered(v)
        val multiLine = v.text.substring(from, to).contains('\n')
        if (!outdent && !multiLine) return insert(v, unit)

        val first = lineStart(v.text, from)
        val lastEnd = lineEnd(v.text, if (to > from && v.text.getOrNull(to - 1) == '\n') to - 1 else to)
        val lines = v.text.substring(first, lastEnd).split('\n')
        var firstShift = 0
        var total = 0
        val changed = lines.mapIndexed { i, line ->
            if (outdent) {
                val removed = when {
                    line.startsWith('\t') -> 1
                    else -> line.takeWhile { it == ' ' }.length.coerceAtMost(unit.length.coerceAtLeast(1))
                }
                if (i == 0) firstShift = -removed
                total -= removed
                line.substring(removed)
            } else {
                if (i == 0) firstShift = unit.length
                total += unit.length
                unit + line
            }
        }.joinToString("\n")
        val next = v.text.substring(0, first) + changed + v.text.substring(lastEnd)
        val start = (from + firstShift).coerceIn(first, next.length)
        val end = (to + total).coerceIn(start, next.length)
        return TextFieldValue(next, if (from == to) TextRange(start) else TextRange(start, end))
    }

    fun move(v: TextFieldValue, move: Move, select: Boolean, word: Boolean = false): TextFieldValue {
        val text = v.text
        val (from, to) = ordered(v)
        val caret = v.selection.end
        // Without Shift, Left/Right on a selection collapse it to that side.
        if (!select && from != to && (move == Move.Left || move == Move.Right)) {
            return v.copy(selection = TextRange(if (move == Move.Left) from else to))
        }
        val target = when (move) {
            Move.Left -> if (word) wordLeft(text, caret) else (caret - 1).coerceAtLeast(0)
            Move.Right -> if (word) wordRight(text, caret) else (caret + 1).coerceAtMost(text.length)
            Move.Home -> if (word) 0 else lineStart(text, caret)
            Move.End -> if (word) text.length else lineEnd(text, caret)
            Move.Up, Move.Down -> {
                val start = lineStart(text, caret)
                val column = caret - start
                if (move == Move.Up) {
                    if (start == 0) 0
                    else {
                        val prevStart = lineStart(text, start - 1)
                        (prevStart + column).coerceAtMost(start - 1)
                    }
                } else {
                    val end = lineEnd(text, caret)
                    if (end == text.length) text.length
                    else (end + 1 + column).coerceAtMost(lineEnd(text, end + 1))
                }
            }
        }
        return v.copy(selection = if (select) TextRange(v.selection.start, target) else TextRange(target))
    }

    fun selectAll(v: TextFieldValue): TextFieldValue = v.copy(selection = TextRange(0, v.text.length))

    fun selected(v: TextFieldValue): String {
        val (from, to) = ordered(v)
        return v.text.substring(from, to)
    }

    /** Wraps the selection in [before]/[after], or puts the caret between them. */
    fun wrap(v: TextFieldValue, before: String, after: String): TextFieldValue {
        val (from, to) = ordered(v)
        val inner = v.text.substring(from, to)
        val next = v.text.substring(0, from) + before + inner + after + v.text.substring(to)
        val caret = from + before.length
        return TextFieldValue(next, if (inner.isEmpty()) TextRange(caret) else TextRange(caret, caret + inner.length))
    }

    /** A fenced code block on its own lines, around the selection if there is one. */
    fun codeBlock(v: TextFieldValue): TextFieldValue {
        val (from, _) = ordered(v)
        val lead = if (from > 0 && v.text[from - 1] != '\n') "\n" else ""
        return wrap(v, "$lead```\n", "\n```\n")
    }

    /**
     * Detects the one character the keyboard just typed, so an armed Ctrl or
     * Shift can turn it into a shortcut. Null for any other edit.
     */
    fun typedChar(old: TextFieldValue, new: TextFieldValue): Char? {
        if (new.text.length != old.text.length + 1 - (old.selection.length)) return null
        val (from, to) = ordered(old)
        if (new.selection != TextRange(from + 1)) return null
        if (new.text.substring(0, from) != old.text.substring(0, from)) return null
        if (new.text.substring(from + 1) != old.text.substring(to)) return null
        return new.text[from]
    }

    private fun ordered(v: TextFieldValue): Pair<Int, Int> =
        minOf(v.selection.start, v.selection.end).coerceIn(0, v.text.length) to
            maxOf(v.selection.start, v.selection.end).coerceIn(0, v.text.length)

    private fun lineStart(text: String, index: Int): Int =
        text.lastIndexOf('\n', (index - 1).coerceAtLeast(-1)).let { if (index == 0) 0 else it + 1 }

    private fun lineEnd(text: String, index: Int): Int =
        text.indexOf('\n', index).let { if (it < 0) text.length else it }

    private fun wordLeft(text: String, index: Int): Int {
        var i = index
        while (i > 0 && !text[i - 1].isLetterOrDigit()) i--
        while (i > 0 && text[i - 1].isLetterOrDigit()) i--
        return i
    }

    private fun wordRight(text: String, index: Int): Int {
        var i = index
        while (i < text.length && !text[i].isLetterOrDigit()) i++
        while (i < text.length && text[i].isLetterOrDigit()) i++
        return i
    }
}
