package com.david.openassistant

import com.david.openassistant.ui.MarkdownBlock
import com.david.openassistant.ui.parseMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun parsesHeadingsListsAndCode() {
        val blocks = parseMarkdownBlocks(
            """
            ## Features
            - Streaming chat
            1. Pick a model

            ```kotlin
            val ready = true
            ```
            """.trimIndent(),
        )

        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.Bullet)
        assertTrue(blocks[2] is MarkdownBlock.Numbered)
        val code = blocks.last() as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val ready = true", code.code)
    }


    @Test
    fun parsesMarkdownTablesAndHorizontalRules() {
        val blocks = parseMarkdownBlocks(
            """
            | Field | Highest | Lowest |
            |---|---|---|
            | Elevation | 20,310 ft | -281.5 ft |

            ---
            """.trimIndent(),
        )

        val table = blocks.first() as MarkdownBlock.Table
        assertEquals(listOf("Field", "Highest", "Lowest"), table.headers)
        assertEquals(listOf("Elevation", "20,310 ft", "-281.5 ft"), table.rows.single())
        assertTrue(blocks.last() is MarkdownBlock.HorizontalRule)
    }

    @Test
    fun preservesPlainParagraphs() {
        val blocks = parseMarkdownBlocks("First line\nsecond line")
        assertEquals(listOf(MarkdownBlock.Paragraph("First line\nsecond line")), blocks)
    }
}
