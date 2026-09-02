package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.domain.ActionMetadata
import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.KeypressAction
import com.phonecontrol.assistant.domain.KeypressKey
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.session.ActionExecutionResult
import com.phonecontrol.assistant.shizuku.ObservationCaptureResult
import com.phonecontrol.assistant.shizuku.TransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SequenceExecutorTest {
    @Test
    fun `carries each verified post observation into the next action`() = runTest {
        val initial = snapshot("obs-0")
        val postOne = snapshot("obs-1")
        val postTwo = snapshot("obs-2")
        val actions = listOf(
            TapAction(10, 20, metadata("Tap first")),
            KeypressAction(KeypressKey.ENTER, metadata("Confirm second")),
        )
        val executedObservationIds = mutableListOf<String>()
        val settledActions = mutableListOf<String>()
        val remembered = mutableListOf<String>()
        val captures = listOf<ObservationCaptureResult>(
            ObservationCaptureResult.Succeeded(postOne, byteArrayOf(1)),
            ObservationCaptureResult.Succeeded(postTwo, byteArrayOf(2)),
        )
        var captureIndex = 0

        val result = SequenceExecutor(
            executeAction = { action, observation ->
                executedObservationIds += "${action.type.name.lowercase()}:${observation.id}:${action.metadata.observationId}"
                ActionExecutionResult.TransportFinished(TransportResult.Succeeded("done"))
            },
            captureAfterAction = { captures[captureIndex++] },
            rememberObservation = { remembered += it.id },
            settleAfterAction = { settledActions += it.type.name.lowercase() },
        ).execute(initial, actions)

        assertTrue(result.ok)
        assertEquals(2, result.completedSteps)
        assertEquals(listOf("tap:obs-0:obs-0", "keypress:obs-1:obs-1"), executedObservationIds)
        assertEquals(listOf("tap", "keypress"), settledActions)
        assertEquals(listOf("obs-1", "obs-2"), remembered)
        assertEquals("obs-2", result.finalObservation?.snapshot?.id)
        assertEquals(listOf("obs-1", "obs-2"), result.steps.mapNotNull { it.observationId })
    }

    @Test
    fun `uses the next step guard regions when capturing the next baseline`() = runTest {
        val guard = GuardRegion(0, 0, 100, 100)
        val requestedGuards = mutableListOf<List<GuardRegion>>()
        val actions = listOf(
            TapAction(10, 20, metadata("Tap first")),
            TapAction(20, 30, metadata("Tap guarded second", guardRegions = listOf(guard))),
        )

        SequenceExecutor(
            executeAction = { _, _ ->
                ActionExecutionResult.TransportFinished(TransportResult.Succeeded("done"))
            },
            captureAfterAction = { guards ->
                requestedGuards += guards
                ObservationCaptureResult.Succeeded(snapshot("post-${requestedGuards.size}"), byteArrayOf(1))
            },
            rememberObservation = {},
            settleAfterAction = {},
        ).execute(snapshot("obs-0"), actions)

        assertEquals(listOf(listOf(guard), listOf(guard)), requestedGuards)
    }

    @Test
    fun `stops before capture and later actions when a step is rejected`() = runTest {
        var executions = 0
        var captures = 0
        val result = SequenceExecutor(
            executeAction = { _, _ ->
                executions += 1
                ActionExecutionResult.TransportFinished(
                    TransportResult.Rejected(
                        code = com.phonecontrol.assistant.shizuku.RejectionCode.STALE_OBSERVATION,
                        message = "stale",
                    ),
                )
            },
            captureAfterAction = {
                captures += 1
                ObservationCaptureResult.Succeeded(snapshot("unexpected"), byteArrayOf(1))
            },
            rememberObservation = {},
            settleAfterAction = {},
        ).execute(
            snapshot("obs-0"),
            listOf(
                TapAction(10, 20, metadata("Tap first")),
                TapAction(20, 30, metadata("Tap second")),
            ),
        )

        assertFalse(result.ok)
        assertEquals(1, executions)
        assertEquals(0, captures)
        assertEquals(0, result.failure?.index)
        assertEquals("STALE_OBSERVATION", result.failure?.code)
        assertEquals("failed", result.failure?.outcome)
        assertEquals(false, result.failure?.executed)
    }

    @Test
    fun `reports post observation failure as unknown and does not continue`() = runTest {
        var executions = 0
        var captures = 0
        val result = SequenceExecutor(
            executeAction = { _, _ ->
                executions += 1
                ActionExecutionResult.TransportFinished(TransportResult.Succeeded("sent"))
            },
            captureAfterAction = {
                captures += 1
                ObservationCaptureResult.Failed("screenshot unavailable")
            },
            rememberObservation = {},
            settleAfterAction = {},
        ).execute(
            snapshot("obs-0"),
            listOf(
                TapAction(10, 20, metadata("Tap first")),
                TapAction(20, 30, metadata("Tap second")),
            ),
        )

        assertFalse(result.ok)
        assertEquals(1, executions)
        assertEquals(1, captures)
        assertEquals("POST_OBSERVATION_FAILED", result.failure?.code)
        assertEquals("unknown", result.failure?.outcome)
        assertNull(result.failure?.executed)
        assertEquals(0, result.completedSteps)
    }

    private fun metadata(
        purpose: String,
        guardRegions: List<GuardRegion> = emptyList(),
    ) = ActionMetadata(
        purpose = purpose,
        observationId = "",
        targetDescription = purpose,
        guardRegions = guardRegions,
    )

    private fun snapshot(id: String) = ObservationSnapshot(
        id = id,
        packageName = "com.example.app",
        activityName = "MainActivity",
        displayId = 0,
        rotation = 0,
        width = 1080,
        height = 2400,
        screenshotFingerprint = id,
    )
}
