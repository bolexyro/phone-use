package com.phonecontrol.assistant.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    @Test
    fun `parseMarkdownBlocks identifies headings and code blocks`() {
        val markdown = """
            # Header 1
            Here is a regular paragraph with **bold** text.
            
            ```kotlin
            val a = 42
            println(a)
            ```
            
            - First bullet
            - Second bullet
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)
        assertTrue(blocks.any { it is MarkdownBlock.Heading && it.level == 1 && it.text == "Header 1" })
        assertTrue(blocks.any { it is MarkdownBlock.Paragraph && it.text.contains("bold") })
        assertTrue(blocks.any { it is MarkdownBlock.CodeBlock && it.language == "kotlin" && it.code.contains("val a = 42") })
        assertTrue(blocks.any { it is MarkdownBlock.UnorderedListItem && it.text == "First bullet" })
        assertTrue(blocks.any { it is MarkdownBlock.UnorderedListItem && it.text == "Second bullet" })
    }

    @Test
    fun `parseMarkdownBlocks identifies ordered lists and blockquotes`() {
        val markdown = """
            > This is a quote
            
            1. Item one
            2. Item two
            
            ---
        """.trimIndent()

        val blocks = parseMarkdownBlocks(markdown)
        assertTrue(blocks.any { it is MarkdownBlock.Blockquote && it.text == "This is a quote" })
        assertTrue(blocks.any { it is MarkdownBlock.OrderedListItem && it.number == "1" && it.text == "Item one" })
        assertTrue(blocks.any { it is MarkdownBlock.OrderedListItem && it.number == "2" && it.text == "Item two" })
        assertTrue(blocks.any { it is MarkdownBlock.HorizontalRule })
    }

    @Test
    fun `parseInlineMarkdown parses styled spans`() {
        val colors = DarkAssistantColors
        val annotated = parseInlineMarkdown("Check `code` and **bold** and [Link](https://example.com)", colors)
        assertEquals("Check  code  and bold and Link", annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
    }
}
