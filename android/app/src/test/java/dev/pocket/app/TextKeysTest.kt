package dev.pocket.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.pocket.app.ui.components.TextKeys
import dev.pocket.app.ui.components.TextKeys.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextKeysTest {
    private fun v(text: String, start: Int, end: Int = start) = TextFieldValue(text, TextRange(start, end))

    @Test
    fun newlineKeepsIndentation() {
        val result = TextKeys.newline(v("fun a() {\n    val x = 1", 23))
        assertEquals("fun a() {\n    val x = 1\n    ", result.text)
        assertEquals(TextRange(28), result.selection)
    }

    @Test
    fun tabInsertsAtCaretAndIndentsSelectedLines() {
        assertEquals("a  b", TextKeys.indent(v("ab", 1), "  ", outdent = false).text)
        val indented = TextKeys.indent(v("one\ntwo\nthree", 0, 7), "  ", outdent = false)
        assertEquals("  one\n  two\nthree", indented.text)
        assertEquals(TextRange(2, 11), indented.selection)
    }

    @Test
    fun shiftTabOutdents() {
        assertEquals("one\n  two", TextKeys.indent(v("  one\n    two", 0, 13), "  ", outdent = true).text)
        val single = TextKeys.indent(v("    x", 4), "  ", outdent = true)
        assertEquals("  x", single.text)
        assertEquals(TextRange(2), single.selection)
    }

    @Test
    fun arrowsMoveByLineAndKeepTheColumn() {
        val text = "abcdef\nxy\nlonger line"
        assertEquals(TextRange(9), TextKeys.move(v(text, 4), Move.Down, select = false).selection)
        assertEquals(TextRange(12), TextKeys.move(v(text, 9), Move.Down, select = false).selection)
        assertEquals(TextRange(2), TextKeys.move(v(text, 9), Move.Up, select = false).selection)
        assertEquals(TextRange(0), TextKeys.move(v(text, 3), Move.Up, select = false).selection)
    }

    @Test
    fun shiftExtendsAndCtrlJumpsWords() {
        val text = "hello big world"
        assertEquals(TextRange(6, 9), TextKeys.move(v(text, 6), Move.Right, select = true, word = true).selection)
        assertEquals(TextRange(10), TextKeys.move(v(text, 14), Move.Left, select = false, word = true).selection)
        assertEquals(TextRange(3, 15), TextKeys.move(v(text, 3), Move.End, select = true).selection)
        // Left on a selection collapses it to its start.
        assertEquals(TextRange(2), TextKeys.move(v(text, 2, 7), Move.Left, select = false).selection)
    }

    @Test
    fun wrapAndCodeBlock() {
        assertEquals("say `hi` now", TextKeys.wrap(v("say hi now", 4, 6), "`", "`").text)
        val block = TextKeys.codeBlock(v("look:", 5))
        assertEquals("look:\n```\n\n```\n", block.text)
        assertEquals(TextRange(10), block.selection)
    }

    @Test
    fun typedCharDetectsOneInsertedLetter() {
        assertEquals('c', TextKeys.typedChar(v("ab", 2), v("abc", 3)))
        assertEquals('x', TextKeys.typedChar(v("a--b", 1, 3), v("axb", 2)))
        assertNull(TextKeys.typedChar(v("ab", 2), v("abcd", 4)))
        assertNull(TextKeys.typedChar(v("abc", 3), v("ab", 2)))
    }
}
