package com.david.openassistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.david.openassistant.ui.MarkdownBlock
import com.david.openassistant.ui.parseMarkdownBlocks

@Composable
fun MarkdownContent(
    content: String,
    modifier: Modifier = Modifier,
    onOpenMission: ((String) -> Unit)? = null,
    onOpenReport: ((String) -> Unit)? = null,
) {
    val blocks = remember(content) { parseMarkdownBlocks(content) }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    text = inlineMarkdown(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                is MarkdownBlock.Paragraph -> Text(
                    text = inlineMarkdown(block.text),
                    style = MaterialTheme.typography.bodyLarge,
                )

                is MarkdownBlock.Bullet -> Row {
                    Text(
                        "• ",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge)
                }

                is MarkdownBlock.Numbered -> Row {
                    Text(
                        "${block.number}. ",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(inlineMarkdown(block.text), style = MaterialTheme.typography.bodyLarge)
                }

                is MarkdownBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text(
                        text = inlineMarkdown(block.text),
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is MarkdownBlock.Code -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = block.language?.uppercase() ?: "CODE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            IconButton(
                                onClick = { clipboardManager.setText(AnnotatedString(block.code)) },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        SelectionContainer {
                            Text(
                                text = highlightSyntax(block.code),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }

                is MarkdownBlock.Table -> MarkdownTable(block)

                is MarkdownBlock.ActionLink -> {
                    val uri = block.uri
                    val isMission = uri.startsWith("mission://")
                    val isReport = uri.startsWith("report://")
                    val id = uri.substringAfter("://")

                    Button(
                        onClick = {
                            if (isMission) onOpenMission?.invoke(id)
                            else if (isReport) onOpenReport?.invoke(id)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        val icon = if (isMission) Icons.AutoMirrored.Filled.Assignment else Icons.Default.Science
                        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(block.label, style = MaterialTheme.typography.labelLarge)
                    }
                }

                MarkdownBlock.HorizontalRule -> HorizontalDivider()
                MarkdownBlock.Spacer -> Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun MarkdownTable(block: MarkdownBlock.Table) {
    val columnCount = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
    val normalizedHeaders = block.headers + List((columnCount - block.headers.size).coerceAtLeast(0)) { "" }
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
    ) {
        Column(Modifier.horizontalScroll(scrollState)) {
            TableRow(normalizedHeaders, header = true)
            block.rows.forEach { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TableRow(row + List((columnCount - row.size).coerceAtLeast(0)) { "" }, header = false)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean) {
    Row {
        cells.forEach { cell ->
            Text(
                text = inlineMarkdown(cell),
                modifier = Modifier
                    .width(TABLE_CELL_WIDTH)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodySmall,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun inlineMarkdown(text: String): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return remember(text, linkColor, codeBackground) {
        buildAnnotatedString {
            var cursor = 0
            INLINE_TOKEN.findAll(text).forEach { match ->
                if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
                val token = match.value
                when {
                    token.startsWith("**") && token.endsWith("**") -> {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(token.removePrefix("**").removeSuffix("**"))
                        }
                    }

                    token.startsWith("`") && token.endsWith("`") -> {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                            append(token.removePrefix("`").removeSuffix("`"))
                        }
                    }

                    token.startsWith("[") -> {
                        val link = INLINE_LINK.matchEntire(token)
                        if (link != null) {
                            val label = link.groupValues[1]
                            val url = link.groupValues[2]
                            withLink(
                                LinkAnnotation.Url(
                                    url = url,
                                    styles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = linkColor,
                                            textDecoration = TextDecoration.Underline,
                                        ),
                                    ),
                                ),
                            ) {
                                append(label)
                            }
                        } else {
                            append(token)
                        }
                    }

                    else -> append(token)
                }
                cursor = match.range.last + 1
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }
}

@Composable
private fun highlightSyntax(code: String): AnnotatedString {
    val keywordColor = MaterialTheme.colorScheme.primary
    val stringColor = Color(0xFF2E7D32)

    return buildAnnotatedString {
        val keywords = listOf("val", "var", "fun", "class", "import", "package", "if", "else", "when", "return", "data", "object")
        val lines = code.lines()
        lines.forEachIndexed { index, line ->
            val tokens = line.split(Regex("(?<=[\\s(),.:])|(?=[\\s(),.:])"))
            tokens.forEach { token ->
                when {
                    token.trim() in keywords -> {
                        withStyle(SpanStyle(color = keywordColor, fontWeight = FontWeight.Bold)) { append(token) }
                    }

                    token.startsWith("\"") && token.endsWith("\"") -> {
                        withStyle(SpanStyle(color = stringColor)) { append(token) }
                    }

                    else -> append(token)
                }
            }
            if (index < lines.lastIndex) append("\n")
        }
    }
}

private val INLINE_TOKEN = Regex("""\*\*[^*\n]+\*\*|`[^`\n]+`|\[[^]\n]+]\(https?://[^)\s]+\)""")
private val INLINE_LINK = Regex("""\[([^]]+)]\((https?://[^)\s]+)\)""")
private val TABLE_CELL_WIDTH = 180.dp
