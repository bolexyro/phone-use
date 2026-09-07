package com.phonecontrol.assistant.developer

/** Quote one already-validated argv value for the ADB shell service. */
internal fun quoteDhdAdbShellArgument(argument: String): String {
    require(argument.isNotEmpty()) { "ADB command arguments must not be empty." }
    require(argument.none { it == '\u0000' || it == '\n' || it == '\r' }) {
        "ADB command arguments cannot contain NUL or line breaks."
    }
    return "'${argument.replace("'", "'\"'\"'")}'"
}

/** Convert only phone-owned argv into one safely quoted ADB shell command. */
internal fun buildDhdAdbShellCommand(command: List<String>): String {
    require(command.isNotEmpty()) { "An ADB command must not be empty." }
    return command.joinToString(" ", transform = ::quoteDhdAdbShellArgument)
}
