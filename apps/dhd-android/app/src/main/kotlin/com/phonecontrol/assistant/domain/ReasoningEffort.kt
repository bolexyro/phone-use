package com.phonecontrol.assistant.domain

/**
 * The reasoning choices exposed by DHD. The Codex value is kept separate from
 * the label so the UI can say "Extra high" while the bridge sends `xhigh`.
 */
enum class ReasoningEffort(
    val storageValue: String,
    val label: String,
    val codexValue: String,
) {
    LIGHT("light", "Light", "low"),
    MEDIUM("medium", "Medium", "medium"),
    HIGH("high", "High", "high"),
    EXTRA_HIGH("extra_high", "Extra high", "xhigh"),
    MAX("max", "Max", "max");

    companion object {
        val default: ReasoningEffort = MAX

        fun fromStorage(value: String?): ReasoningEffort =
            entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() } ?: default

        fun fromCodexValue(value: String?): ReasoningEffort? =
            entries.firstOrNull { it.codexValue == value?.trim()?.lowercase() }
    }
}
