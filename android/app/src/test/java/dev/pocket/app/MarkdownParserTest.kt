package dev.pocket.app

import androidx.compose.ui.text.font.FontWeight
import dev.pocket.app.ui.chats.MdBlock
import dev.pocket.app.ui.chats.inlineMarkdown
import dev.pocket.app.ui.chats.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun parsesTheBlocksAgentsWrite() {
        val blocks = parseMarkdown(
            """
            ## Plan
            First paragraph
            continues here.

            - one
              wrapped
            - two
              1. nested

            ```kotlin
            val x = 1
            ```

            > quoted

            | File | Change |
            | --- | --- |
            | a.kt | added |

            ---
            """.trimIndent()
        )
        assertEquals(
            listOf(
                MdBlock.Heading(2, "Plan"),
                MdBlock.Paragraph("First paragraph\ncontinues here."),
                MdBlock.ListItem("•", "one wrapped", 0),
                MdBlock.ListItem("•", "two", 0),
                MdBlock.ListItem("1.", "nested", 1),
                MdBlock.Code("kotlin", "val x = 1"),
                MdBlock.Quote("quoted"),
                MdBlock.Table(listOf("File", "Change"), listOf(listOf("a.kt", "added"))),
                MdBlock.Rule,
            ),
            blocks,
        )
    }

    @Test
    fun unclosedFenceKeepsStreamingCodeReadable() {
        assertEquals(
            listOf(MdBlock.Paragraph("Here:"), MdBlock.Code("ts", "const a = 1;\nconst b")),
            parseMarkdown("Here:\n```ts\nconst a = 1;\nconst b"),
        )
    }

    @Test
    fun inlineStylesStripMarkers() {
        val text = inlineMarkdown("Use **bold**, *it*, `code`, ~~old~~ and [docs](https://x.dev).")
        assertEquals("Use bold, it, code, old and docs.", text.text)
        val bold = text.spanStyles.first { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals("bold", text.text.substring(bold.start, bold.end))
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
    }

    @Test
    fun snakeCaseAndMathAreNotEmphasis() {
        assertEquals("call my_var_name and 2*3*4", inlineMarkdown("call my_var_name and 2*3*4").text)
        assertTrue(inlineMarkdown("my_var_name").spanStyles.isEmpty())
    }
}
