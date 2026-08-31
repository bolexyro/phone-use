package com.phonecontrol.assistant.session

private const val MAX_COMPLETION_PREVIEW_CHARS = 160

const val DEFAULT_COMPLETION_NOTIFICATION_PREVIEW = "Your DHD task is ready to review."

/**
 * Turn the full assistant answer into the short text shown in a completion
 * notification. The full answer remains in the conversation timeline.
 */
fun completionNotificationPreview(message: String): String {
    val cleaned = message
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .filterNot { it.matches(Regex("^#{1,6}\\s+.*$")) }
        .map { line ->
            line
                .replace(Regex("^[-*+]\\s+"), "")
                .replace(Regex("^\\d+[.)]\\s+"), "")
                .replace(Regex("^>\\s*"), "")
                .replace(Regex("[`*_]"), "")
                .trim()
        }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()

    if (cleaned.isEmpty()) return DEFAULT_COMPLETION_NOTIFICATION_PREVIEW

    val firstSentence = Regex("^.*?[.!?](?=\\s|$)").find(cleaned)?.value?.trim()
        ?: cleaned
    return shortenCompletionPreview(firstSentence)
        .ifBlank { DEFAULT_COMPLETION_NOTIFICATION_PREVIEW }
}

private fun shortenCompletionPreview(value: String): String {
    if (value.length <= MAX_COMPLETION_PREVIEW_CHARS) return value

    val clipped = value.take(MAX_COMPLETION_PREVIEW_CHARS - 1).trimEnd()
    val wordBoundary = clipped.lastIndexOf(' ')
    val readable = if (wordBoundary >= MAX_COMPLETION_PREVIEW_CHARS / 2) {
        clipped.take(wordBoundary).trimEnd()
    } else {
        clipped
    }
    return "$readable…"
}
