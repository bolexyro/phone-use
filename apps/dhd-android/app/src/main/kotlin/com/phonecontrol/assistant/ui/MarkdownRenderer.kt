package com.phonecontrol.assistant.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock
    data class Blockquote(val text: String) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
    data class UnorderedListItem(val level: Int, val text: String) : MarkdownBlock
    data class OrderedListItem(val number: String, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

fun parseInlineMarkdown(
    text: String,
    colors: AssistantColorScheme,
): AnnotatedString {
    return buildAnnotatedString {
        val pattern = Regex("""(\*\*\*[^*]+\*\*\*|___[^_]+___|\*\*[^*]+\*\*|__[^_]+__|(?<!\*)\*[^*]+\*(?!\*)|(?<!_)_[^_]+_(?!_)|`[^`]+`|\[([^\]]+)\]\(([^)]+)\))""")

        var currentIndex = 0
        val matches = pattern.findAll(text)

        for (match in matches) {
            if (match.range.first > currentIndex) {
                append(text.substring(currentIndex, match.range.first))
            }

            val matchText = match.value
            when {
                // Bold + Italic: ***text*** or ___text___
                (matchText.startsWith("***") && matchText.endsWith("***") && matchText.length >= 6) ||
                (matchText.startsWith("___") && matchText.endsWith("___") && matchText.length >= 6) -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(matchText.substring(3, matchText.length - 3))
                    }
                }
                // Bold: **text** or __text__
                (matchText.startsWith("**") && matchText.endsWith("**") && matchText.length >= 4) ||
                (matchText.startsWith("__") && matchText.endsWith("__") && matchText.length >= 4) -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(matchText.substring(2, matchText.length - 2))
                    }
                }
                // Italic: *text* or _text_
                (matchText.startsWith("*") && matchText.endsWith("*") && matchText.length >= 2) ||
                (matchText.startsWith("_") && matchText.endsWith("_") && matchText.length >= 2) -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(matchText.substring(1, matchText.length - 1))
                    }
                }
                // Inline Code: `code`
                matchText.startsWith("`") && matchText.endsWith("`") && matchText.length >= 2 -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = if (colors.isDark) Color(0xFF2C2C2E) else Color(0xFFE5E7EB),
                            color = colors.accentBlue,
                            fontSize = 13.5.sp,
                        ),
                    ) {
                        append(" ${matchText.substring(1, matchText.length - 1)} ")
                    }
                }
                // Link: [text](url)
                matchText.startsWith("[") && match.groupValues.size >= 3 -> {
                    val linkText = match.groupValues[2]
                    withStyle(
                        SpanStyle(
                            color = colors.accentBlue,
                            textDecoration = TextDecoration.Underline,
                        ),
                    ) {
                        append(linkText)
                    }
                }
                else -> {
                    append(matchText)
                }
            }
            currentIndex = match.range.last + 1
        }

        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}

fun parseMarkdownBlocks(rawMarkdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = rawMarkdown.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        // 1. Code Block
        if (trimmed.startsWith("```")) {
            val language = trimmed.removePrefix("```").trim().ifBlank { null }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size && lines[i].trim().startsWith("```")) {
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(language, codeLines.joinToString("\n")))
            continue
        }

        // 2. Horizontal Rule
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            blocks.add(MarkdownBlock.HorizontalRule)
            i++
            continue
        }

        // 3. Headings
        if (trimmed.startsWith("### ")) {
            blocks.add(MarkdownBlock.Heading(3, trimmed.removePrefix("### ").trim()))
            i++
            continue
        }
        if (trimmed.startsWith("## ")) {
            blocks.add(MarkdownBlock.Heading(2, trimmed.removePrefix("## ").trim()))
            i++
            continue
        }
        if (trimmed.startsWith("# ")) {
            blocks.add(MarkdownBlock.Heading(1, trimmed.removePrefix("# ").trim()))
            i++
            continue
        }

        // 4. Blockquote
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            quoteLines.add(trimmed.removePrefix(">").trim())
            i++
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines.add(lines[i].trim().removePrefix(">").trim())
                i++
            }
            blocks.add(MarkdownBlock.Blockquote(quoteLines.joinToString(" ")))
            continue
        }

        // 5. Unordered List Items
        val unorderedMatch = Regex("""^(\s*)[-*+]\s+(.*)$""").matchEntire(line)
        if (unorderedMatch != null) {
            val indent = unorderedMatch.groupValues[1].length / 2
            val itemText = unorderedMatch.groupValues[2]
            blocks.add(MarkdownBlock.UnorderedListItem(indent, itemText))
            i++
            continue
        }

        // 6. Ordered List Items
        val orderedMatch = Regex("""^(\s*)(\d+)\.\s+(.*)$""").matchEntire(line)
        if (orderedMatch != null) {
            val number = orderedMatch.groupValues[2]
            val itemText = orderedMatch.groupValues[3]
            blocks.add(MarkdownBlock.OrderedListItem(number, itemText))
            i++
            continue
        }

        // 7. Empty line
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // 8. Paragraph
        val paragraphLines = mutableListOf<String>()
        paragraphLines.add(trimmed)
        i++
        while (i < lines.size) {
            val nextTrimmed = lines[i].trim()
            if (nextTrimmed.isEmpty() ||
                nextTrimmed.startsWith("```") ||
                nextTrimmed.startsWith("#") ||
                nextTrimmed.startsWith(">") ||
                nextTrimmed.startsWith("- ") ||
                nextTrimmed.startsWith("* ") ||
                nextTrimmed.startsWith("+ ") ||
                Regex("""^\d+\.\s+""").containsMatchIn(nextTrimmed) ||
                nextTrimmed == "---" || nextTrimmed == "***" || nextTrimmed == "___"
            ) {
                break
            }
            paragraphLines.add(nextTrimmed)
            i++
        }
        blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString(" ")))
    }

    return blocks
}

@Composable
fun MarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier,
    baseTextStyle: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
) {
    val colors = LocalAssistantColors.current
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val fontSize = when (block.level) {
                        1 -> 19.sp
                        2 -> 17.sp
                        else -> 15.5.sp
                    }
                    Text(
                        text = parseInlineMarkdown(block.text, colors),
                        style = baseTextStyle.copy(
                            fontSize = fontSize,
                            fontWeight = FontWeight.Bold,
                            color = colors.textPrimary,
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                is MarkdownBlock.CodeBlock -> {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colors.surfaceCard,
                        border = BorderStroke(1.dp, colors.borderColor),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            if (!block.language.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (colors.isDark) Color(0xFF1E1E1E) else Color(0xFFEAEAED))
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = block.language.lowercase(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.textSecondary,
                                    )
                                }
                                HorizontalDivider(color = colors.borderColor)
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = block.code,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp,
                                    color = colors.textPrimary,
                                )
                            }
                        }
                    }
                }

                is MarkdownBlock.Blockquote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(22.dp)
                                .background(colors.accentBlue, RoundedCornerShape(2.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = parseInlineMarkdown(block.text, colors),
                            style = baseTextStyle.copy(
                                fontStyle = FontStyle.Italic,
                                color = colors.textSecondary,
                            ),
                        )
                    }
                }

                is MarkdownBlock.HorizontalRule -> {
                    HorizontalDivider(
                        color = colors.borderColor,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }

                is MarkdownBlock.UnorderedListItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (block.level * 16 + 4).dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "•",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.accentBlue,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, colors),
                            style = baseTextStyle.copy(color = colors.textPrimary),
                        )
                    }
                }

                is MarkdownBlock.OrderedListItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${block.number}.",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accentBlue,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            text = parseInlineMarkdown(block.text, colors),
                            style = baseTextStyle.copy(color = colors.textPrimary),
                        )
                    }
                }

                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(block.text, colors),
                        style = baseTextStyle.copy(color = colors.textPrimary),
                    )
                }
            }
        }
    }
}
