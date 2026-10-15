package dev.pocket.app.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Reading size for agent replies: a little larger than body text, with room between lines. */
val ChatTextStyle = TextStyle(fontSize = 15.sp, lineHeight = 23.sp)

@Composable
fun MarkdownContent(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val colors = MaterialTheme.colorScheme
    val styles = remember(colors) {
        InlineStyles(
            code = SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.5.sp,
                background = colors.onSurface.copy(alpha = 0.09f),
                color = colors.onSurface,
            ),
            link = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline),
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    inlineMarkdown(block.text, styles),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        else -> ChatTextStyle.copy(fontWeight = FontWeight.SemiBold)
                    },
                    modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 0.dp),
                )
                is MdBlock.Paragraph -> Text(inlineMarkdown(block.text, styles), style = ChatTextStyle)
                is MdBlock.Code -> CodeBlock(block.language, block.code)
                is MdBlock.ListItem -> ListItemRow(block, styles)
                is MdBlock.Quote -> QuoteBlock(inlineMarkdown(block.text, styles))
                is MdBlock.Table -> TableBlock(block, styles)
                MdBlock.Rule -> HorizontalDivider(color = colors.outlineVariant)
            }
        }
    }
}

@Composable
private fun ListItemRow(item: MdBlock.ListItem, styles: InlineStyles) {
    Row(Modifier.padding(start = (item.depth * 16).dp)) {
        Text(
            if (item.marker == "•" && item.depth > 0) "◦" else item.marker,
            style = ChatTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 20.dp).padding(end = 6.dp),
        )
        Text(inlineMarkdown(item.text, styles), style = ChatTextStyle, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun QuoteBlock(text: AnnotatedString) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
        )
        Text(
            text,
            style = ChatTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(code))
                        copied = true
                    },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy code",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Text(
                code,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun TableBlock(table: MdBlock.Table, styles: InlineStyles) {
    val columns = table.header.size
    // Size columns by their longest cell so narrow tables stay compact.
    val widths = remember(table) {
        List(columns) { col ->
            val longest = (listOf(table.header) + table.rows).maxOf { it.getOrElse(col) { "" }.length }
            (longest * 8 + 24).coerceIn(56, 260).dp
        }
    }
    val line = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        Column(Modifier.border(1.dp, line, RoundedCornerShape(8.dp))) {
            (listOf(table.header) + table.rows).forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = line)
                Row(
                    Modifier.background(
                        if (index == 0) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                    ),
                ) {
                    row.forEachIndexed { col, cell ->
                        Text(
                            inlineMarkdown(cell, styles),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                            modifier = Modifier
                                .width(widths.getOrElse(col) { 80.dp })
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
