package com.david.openassistant.ui

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Numbered(val number: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class Code(val language: String?, val code: String) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data class ActionLink(val label: String, val uri: String) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
    data object Spacer : MarkdownBlock
}

internal fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    if (content.isBlank()) return emptyList()

    val blocks = mutableListOf<MarkdownBlock>()
    val paragraphLines = mutableListOf<String>()
    val codeLines = mutableListOf<String>()
    val lines = content.lines()
    var inCode = false
    var codeLanguage: String? = null
    var index = 0

    fun flushParagraph() {
        if (paragraphLines.isEmpty()) return
        val text = paragraphLines.joinToString("\n")
        val actionLinkMatch = ACTION_LINK_REGEX.matchEntire(text.trim())
        if (actionLinkMatch != null) {
            blocks += MarkdownBlock.ActionLink(
                label = actionLinkMatch.groupValues[1],
                uri = actionLinkMatch.groupValues[2],
            )
        } else {
            blocks += MarkdownBlock.Paragraph(text)
        }
        paragraphLines.clear()
    }

    fun flushCode() {
        blocks += MarkdownBlock.Code(
            language = codeLanguage?.takeIf(String::isNotBlank),
            code = codeLines.joinToString("\n"),
        )
        codeLines.clear()
        codeLanguage = null
    }

    while (index < lines.size) {
        val rawLine = lines[index]
        val trimmed = rawLine.trim()

        if (trimmed.startsWith("```")) {
            if (inCode) {
                flushCode()
                inCode = false
            } else {
                flushParagraph()
                codeLanguage = trimmed.removePrefix("```").trim().ifBlank { null }
                inCode = true
            }
            index += 1
            continue
        }

        if (inCode) {
            codeLines += rawLine
            index += 1
            continue
        }

        if (isTableStart(lines, index)) {
            flushParagraph()
            val headers = parseTableRow(lines[index])
            index += 2 // header + delimiter row
            val rows = mutableListOf<List<String>>()
            while (index < lines.size && isTableDataRow(lines[index])) {
                rows += parseTableRow(lines[index])
                index += 1
            }
            blocks += MarkdownBlock.Table(headers, rows)
            continue
        }

        val heading = HEADING_REGEX.matchEntire(rawLine)
        val bullet = BULLET_REGEX.matchEntire(rawLine)
        val numbered = NUMBERED_REGEX.matchEntire(rawLine)
        when {
            heading != null -> {
                flushParagraph()
                blocks += MarkdownBlock.Heading(
                    level = heading.groupValues[1].length,
                    text = heading.groupValues[2].trim(),
                )
            }

            bullet != null -> {
                flushParagraph()
                blocks += MarkdownBlock.Bullet(bullet.groupValues[1].trim())
            }

            numbered != null -> {
                flushParagraph()
                blocks += MarkdownBlock.Numbered(
                    number = numbered.groupValues[1],
                    text = numbered.groupValues[2].trim(),
                )
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                blocks += MarkdownBlock.Quote(trimmed.removePrefix(">").trim())
            }

            HORIZONTAL_RULE_REGEX.matches(trimmed) -> {
                flushParagraph()
                blocks += MarkdownBlock.HorizontalRule
            }

            trimmed.isBlank() -> {
                flushParagraph()
                if (blocks.lastOrNull() != MarkdownBlock.Spacer) blocks += MarkdownBlock.Spacer
            }

            else -> paragraphLines += rawLine.trimEnd()
        }
        index += 1
    }

    if (inCode) flushCode() else flushParagraph()
    return blocks.dropLastWhile { it == MarkdownBlock.Spacer }
}

private fun isTableStart(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    val header = lines[index].trim()
    val delimiter = lines[index + 1].trim()
    return header.contains('|') && TABLE_DELIMITER_REGEX.matches(delimiter)
}

private fun isTableDataRow(line: String): Boolean {
    val trimmed = line.trim()
    return trimmed.isNotBlank() && trimmed.contains('|') && !HEADING_REGEX.matches(line)
}

internal fun parseTableRow(line: String): List<String> = line
    .trim()
    .removePrefix("|")
    .removeSuffix("|")
    .split('|')
    .map { it.trim() }

private val HEADING_REGEX = Regex("^\\s*(#{1,6})\\s+(.+)$")
private val BULLET_REGEX = Regex("^\\s*[-*+]\\s+(.+)$")
private val NUMBERED_REGEX = Regex("^\\s*(\\d+)[.)]\\s+(.+)$")
private val ACTION_LINK_REGEX = Regex("^\\[(.*?)]\\((mission|report)://([a-zA-Z0-9_-]+)\\)$")
private val TABLE_DELIMITER_REGEX = Regex("^\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?$")
private val HORIZONTAL_RULE_REGEX = Regex("^(?:-{3,}|\\*{3,}|_{3,})$")
