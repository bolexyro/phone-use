package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.domain.ActionMetadata
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.shizuku.PhoneActionTransport
import com.phonecontrol.assistant.shizuku.TransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `executes action and updates timeline`() = runTest {
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

        assertTrue(result is ActionExecutionResult.TransportFinished)
        assertTrue(coordinator.events.value.any { it.kind == com.phonecontrol.assistant.domain.ActivityEventKind.ACTION_SUCCEEDED })
    }

    @Test
    fun `desktop request can be claimed once and released for retry`() {
        val coordinator = coordinator()
        coordinator.start("Find a restaurant")

        val pending = coordinator.pendingRequest()
        assertNotNull(pending)
        val claimed = coordinator.claimRequest(pending!!.sessionId)
        assertEquals(pending, claimed)
        assertNull(coordinator.pendingRequest())
        assertNull(coordinator.claimRequest(pending.sessionId))

        assertTrue(coordinator.releaseRequest(pending.sessionId))
        assertEquals(pending, coordinator.pendingRequest())
    }

    @Test
    fun `desktop request stays queued until phone actions are ready`() {
        var phoneActionsReady = false
        val coordinator = coordinator { phoneActionsReady }
        coordinator.start("Open Spotify")

        assertNull(coordinator.pendingRequest())

        phoneActionsReady = true
        val pending = coordinator.pendingRequest()
        assertNotNull(pending)

        phoneActionsReady = false
        assertNull(coordinator.claimRequest(pending!!.sessionId))

        phoneActionsReady = true
        assertEquals(pending, coordinator.claimRequest(pending.sessionId))
    }

    @Test
    fun `steer request can be claimed once and completed`() {
        val coordinator = coordinator()
        assertTrue(coordinator.start("Find a restaurant"))

        val queued = coordinator.enqueueSteer("Actually use the closest location.")
        assertNotNull(queued)
        assertEquals(queued, coordinator.pendingSteer(queued!!.sessionId))

        val claimed = coordinator.claimSteer(queued.sessionId, queued.steerId)
        assertEquals(queued, claimed)
        assertNull(coordinator.pendingSteer(queued.sessionId))
        assertTrue(coordinator.completeSteer(queued.sessionId, queued.steerId))
        assertNull(coordinator.claimSteer(queued.sessionId, queued.steerId))
    }

    @Test
    fun `failed steer delivery is requeued for the same running session`() {
        val coordinator = coordinator()
        assertTrue(coordinator.start("Find a restaurant"))

        val queued = coordinator.enqueueSteer("Do not submit anything yet.")!!
        assertNotNull(coordinator.claimSteer(queued.sessionId, queued.steerId))
        assertTrue(coordinator.releaseSteer(queued.sessionId, queued.steerId))
        assertEquals(queued, coordinator.pendingSteer(queued.sessionId))
    }

    @Test
    fun `steer cannot be released through a different session id`() {
        val coordinator = coordinator()
        assertTrue(coordinator.start("Find a restaurant"))

        val queued = coordinator.enqueueSteer("Keep the current plan.")!!
        assertNotNull(coordinator.claimSteer(queued.sessionId, queued.steerId))
        assertTrue(!coordinator.releaseSteer("another-session", queued.steerId))
        assertNull(coordinator.pendingSteer(queued.sessionId))
        assertTrue(coordinator.releaseSteer(queued.sessionId, queued.steerId))
        assertEquals(queued, coordinator.pendingSteer(queued.sessionId))
    }

    @Test
    fun `agent feedback is kept as a conversation message when completed`() {
        val coordinator = coordinator()
        coordinator.start("Find a restaurant")

        assertTrue(
            coordinator.complete(
                message = "Fallback completion",
                agentFeedback = "I stopped because the screen changed before the tap.",
            ),
        )

        val state = coordinator.state.value as SessionState.Completed
        assertEquals("I stopped because the screen changed before the tap.", state.message)
        assertTrue(coordinator.events.value.any {
            it.kind == com.phonecontrol.assistant.domain.ActivityEventKind.AGENT_MESSAGE &&
                it.message == state.message
        })
    }

    @Test
    fun `attention request creates a user-visible attention event`() {
        val coordinator = coordinator()
        coordinator.start("Find a restaurant")

        assertTrue(coordinator.requestAttention("The screen changed; please review the phone."))

        assertTrue(coordinator.state.value is SessionState.Running)
        assertEquals("Needs your attention", (coordinator.state.value as SessionState.Running).currentPurpose)
        assertTrue(coordinator.events.value.any {
            it.kind == com.phonecontrol.assistant.domain.ActivityEventKind.ATTENTION_REQUIRED
        })
    }

    private fun coordinator(phoneActionsReady: () -> Boolean = { true }): SessionCoordinator = SessionCoordinator(
        enabledPackagesProvider = { setOf("com.example.shop") },
        policyEngine = PolicyEngine(),
        transport = object : PhoneActionTransport {
            override suspend fun execute(
                action: com.phonecontrol.assistant.domain.PhoneAction,
                observation: ObservationSnapshot?,
            ): TransportResult = TransportResult.Succeeded("executed")
        },
        phoneActionsReadyProvider = phoneActionsReady,
    )
}
