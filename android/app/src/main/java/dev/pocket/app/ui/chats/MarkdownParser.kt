package dev.pocket.app.ui.chats

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** The subset of Markdown that agents write: enough to make replies readable on a phone. */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val language: String, val code: String) : MdBlock()
    data class ListItem(val marker: String, val text: String, val depth: Int) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    data object Rule : MdBlock()
}

private val headingRe = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
private val ruleRe = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
private val listRe = Regex("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(.*)$")
private val tableDividerRe = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.joinToString("\n").trim())
            paragraph.clear()
        }
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        // Fenced code. An unclosed fence runs to the end, which keeps a reply
        // that is still streaming readable.
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            flushParagraph()
            val fence = trimmed.takeWhile { it == trimmed[0] }
            val language = trimmed.removePrefix(fence).trim().substringBefore(' ')
            val indent = line.length - trimmed.length
            val code = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                code += lines[i].removePrefix(" ".repeat(indent))
                i++
            }
            blocks += MdBlock.Code(language, code.joinToString("\n").trimEnd())
            i++
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            i++
            continue
        }

        val heading = headingRe.matchEntire(trimmed)
        if (heading != null) {
            flushParagraph()
            blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            i++
            continue
        }

        if (ruleRe.matches(line)) {
            flushParagraph()
            blocks += MdBlock.Rule
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            flushParagraph()
            val quote = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                quote += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                i++
            }
            blocks += MdBlock.Quote(quote.joinToString("\n").trim())
            continue
        }

        if (line.contains('|') && i + 1 < lines.size && tableDividerRe.matches(lines[i + 1])) {
            flushParagraph()
            val header = tableCells(line)
            val rows = mutableListOf<List<String>>()
            i += 2
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                val cells = tableCells(lines[i])
                rows += List(header.size) { cells.getOrElse(it) { "" } }
                i++
            }
            blocks += MdBlock.Table(header, rows)
            continue
        }

        val item = listRe.matchEntire(line)
        if (item != null) {
            flushParagraph()
            val depth = item.groupValues[1].replace("\t", "    ").length / 2
            val raw = item.groupValues[2]
            val marker = if (raw[0].isDigit()) raw.dropLast(1) + "." else "•"
            val text = StringBuilder(item.groupValues[3])
            i++
            // Indented lines that are not new items continue this item.
            while (i < lines.size && lines[i].isNotBlank() && lines[i].startsWith("  ") &&
                listRe.matchEntire(lines[i]) == null && !lines[i].trimStart().startsWith("```")
            ) {
                text.append(' ').append(lines[i].trim())
                i++
            }
            blocks += MdBlock.ListItem(marker, text.toString(), depth.coerceAtMost(4))
            continue
        }

        paragraph += line
        i++
    }
    flushParagraph()
    return blocks
}

private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

/** Colors for inline Markdown, supplied by the theme. */
data class InlineStyles(
    val code: SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace),
    val link: SpanStyle = SpanStyle(textDecoration = TextDecoration.Underline),
)

/** Inline Markdown: `code`, **bold**, *italic*, ~~strike~~ and [links](url). */
fun inlineMarkdown(text: String, styles: InlineStyles = InlineStyles()): AnnotatedString =
    buildAnnotatedString { appendInline(text, styles) }

private fun AnnotatedString.Builder.appendInline(text: String, styles: InlineStyles) {
    var i = 0
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            append(plain.toString())
            plain.clear()
        }
    }

    while (i < text.length) {
        val c = text[i]

        if (c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE) {
            plain.append(text[i + 1])
            i += 2
            continue
        }

        if (c == '`') {
            val ticks = text.drop(i).takeWhile { it == '`' }.length
            val fence = "`".repeat(ticks)
            val end = text.indexOf(fence, i + ticks)
            if (end > i) {
                flush()
                withStyle(styles.code) { append(text.substring(i + ticks, end).trim()) }
                i = end + ticks
                continue
            }
        }

        if ((c == '*' || c == '_') && text.startsWith("$c$c", i)) {
            val end = text.indexOf("$c$c", i + 2)
            if (end > i + 2) {
                flush()
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(text.substring(i + 2, end), styles) }
                i = end + 2
                continue
            }
        }

        if (c == '~' && text.startsWith("~~", i)) {
            val end = text.indexOf("~~", i + 2)
            if (end > i + 2) {
                flush()
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(text.substring(i + 2, end), styles) }
                i = end + 2
                continue
            }
        }

        if (c == '*' || c == '_') {
            // snake_case and 2*3*4 are not emphasis.
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + 1)
            val opens = after != null && !after.isWhitespace() && (before == null || !before.isLetterOrDigit())
            val end = if (opens) closingEmphasis(text, i + 1, c) else -1
            if (end > i + 1) {
                flush()
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(text.substring(i + 1, end), styles) }
                i = end + 1
                continue
            }
        }

        if (c == '[') {
            val close = text.indexOf(']', i + 1)
            if (close > i && text.getOrNull(close + 1) == '(') {
                val urlEnd = text.indexOf(')', close + 2)
                if (urlEnd > close) {
                    val label = text.substring(i + 1, close)
                    val url = text.substring(close + 2, urlEnd).trim()
                    flush()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        withLink(LinkAnnotation.Url(url, TextLinkStyles(styles.link))) { appendInline(label, styles) }
                    } else {
                        withStyle(styles.code) { append(label) }
                    }
                    i = urlEnd + 1
                    continue
                }
            }
        }

        plain.append(c)
        i++
    }
    flush()
}

private fun closingEmphasis(text: String, from: Int, marker: Char): Int {
    var j = from
    while (j < text.length) {
        j = text.indexOf(marker, j)
        if (j < 0) return -1
        val doubled = text.getOrNull(j + 1) == marker || text.getOrNull(j - 1) == marker
        val after = text.getOrNull(j + 1)
        val closes = !text[j - 1].isWhitespace() && (after == null || !after.isLetterOrDigit())
        if (!doubled && closes) return j
        j++
    }
    return -1
}

private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~>"
