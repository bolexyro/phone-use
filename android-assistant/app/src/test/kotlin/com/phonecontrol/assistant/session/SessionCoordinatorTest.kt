package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.domain.ActionMetadata
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.shizuku.PhoneActionTransport
import com.phonecontrol.assistant.shizuku.TransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCoordinatorTest {
    private val observation = ObservationSnapshot(
        id = "obs-1",
        packageName = "com.example.shop",
        activityName = null,
        displayId = 0,
        rotation = 0,
        width = 1080,
        height = 2400,
        screenshotFingerprint = "fingerprint",
    )

    @Test
    fun `start pause resume and stop update state and timeline`() {
        val coordinator = coordinator()

        assertTrue(coordinator.start("Find a restaurant"))
        assertTrue(coordinator.pause())
        assertTrue(coordinator.resume())
        assertTrue(coordinator.stop())

        assertTrue(coordinator.state.value is SessionState.Stopped)
        assertEquals(4, coordinator.events.value.size)
    }

    @Test
    fun `sensitive action stops at confirmation`() = runTest {
        val coordinator = coordinator()
        coordinator.start("Buy dinner")

        val result = coordinator.executeAction(
            TapAction(
                x = 500,
                y = 900,
                metadata = ActionMetadata(
                    purpose = "Place order",
                    observationId = observation.id,
                    targetDescription = "Place order button",
                ),
            ),
            observation,
        )

        assertTrue(result is ActionExecutionResult.ConfirmationRequired)
        assertTrue(coordinator.events.value.any { it.kind.name == "CONFIRMATION_REQUIRED" })
    }

    private fun coordinator(): SessionCoordinator = SessionCoordinator(
        enabledPackagesProvider = { setOf("com.example.shop") },
        policyEngine = PolicyEngine(),
        transport = object : PhoneActionTransport {
            override suspend fun execute(
                action: com.phonecontrol.assistant.domain.PhoneAction,
                observation: ObservationSnapshot?,
            ): TransportResult = TransportResult.Succeeded("executed")
        },
    )
}
