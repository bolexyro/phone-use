package com.phonecontrol.assistant.domain

/**
 * Describes whether the task-display observation can be trusted visually.
 *
 * A secure window can still be focused and receive input, but Android is
 * allowed to hide its pixels from screenshots and non-secure displays. DHD
 * therefore reports this separately from the foreground package/activity so
 * the agent can hand the step to the user instead of guessing at coordinates.
 */
enum class ScreenProtectionStatus {
    VISIBLE,
    SECURE,
    BLANK_UNKNOWN,
}
data class ScreenProtection(
    val status: ScreenProtectionStatus = ScreenProtectionStatus.VISIBLE,
    val requiresUserAttention: Boolean = false,
    val signals: List<String> = emptyList(),
    val reason: String? = null,
) {
    companion object {
        val VISIBLE = ScreenProtection()
    }
}
