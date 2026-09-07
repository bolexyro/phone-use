package com.phonecontrol.assistant.developer

import com.phonecontrol.assistant.execution.PhoneProcessResult
import com.phonecontrol.assistant.execution.PhoneProcessRunner

/** Adapts typed phone argv to DHD's local ADB controller. */
class DhdAdbProcessRunner(
    private val controller: DhdAdbController,
) : PhoneProcessRunner {
    override suspend fun run(command: List<String>): PhoneProcessResult {
        require(command.isNotEmpty()) { "An ADB command must not be empty." }
        val binaryOutput = command.size == 2 && command[0] == "screencap" && command[1] == "-p"
        return controller.execute(command, binaryOutput = binaryOutput)
    }
}
