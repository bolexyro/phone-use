package com.phonecontrol.assistant.execution

/** Result of one command sent through the phone's local privileged bridge. */
data class PhoneProcessResult(
    val exitCode: Int?,
    val stdout: ByteArray,
    val stderr: String,
    val timedOut: Boolean = false,
)

/**
 * Narrow command boundary used by the observation and typed-action layers.
 * Implementations must only receive argv assembled by the phone-side code;
 * model or desktop text must never be passed here as a shell command.
 */
interface PhoneProcessRunner {
    suspend fun run(command: List<String>): PhoneProcessResult
}
