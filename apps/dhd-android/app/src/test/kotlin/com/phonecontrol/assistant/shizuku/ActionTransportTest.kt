package com.phonecontrol.assistant.shizuku

import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.ObservationSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionTransportTest {
    private val guard = GuardRegion(left = 100, top = 200, right = 400, bottom = 500)

    private val observation = ObservationSnapshot(
        id = "obs-1",
        packageName = "com.example.shop",
        activityName = "ShopActivity",
        displayId = 0,
        rotation = 0,
        width = 1080,
        height = 2400,
        screenshotFingerprint = "before",
        guardFingerprints = mapOf(guard to "guard-before"),
    )

    @Test
    fun `unchanged structure is fresh even when unguarded screen pixels changed`() {
        val current = observation.copy(
            id = "obs-2",
            screenshotFingerprint = "after",
        )

        assertFalse(isObservationStale(observation, current, emptyList()))
    }

    @Test
    fun `rotation change is stale without guard regions`() {
        val current = observation.copy(id = "obs-2", rotation = 1)

        assertTrue(isObservationStale(observation, current, emptyList()))
    }

    @Test
    fun `guard region change is stale when strict region checking is requested`() {
        val current = observation.copy(
            id = "obs-2",
            screenshotFingerprint = "different-outside-guard",
            guardFingerprints = mapOf(guard to "guard-after"),
        )

        assertTrue(isObservationStale(observation, current, listOf(guard)))
    }

    @Test
    fun `changes outside guarded regions remain fresh`() {
        val current = observation.copy(
            id = "obs-2",
            screenshotFingerprint = "different-outside-guard",
            guardFingerprints = mapOf(guard to "guard-before"),
        )

        assertFalse(isObservationStale(observation, current, listOf(guard)))
    }

    @Test
    fun `missing guard fingerprint is stale when strict region checking is requested`() {
        val current = observation.copy(id = "obs-2", guardFingerprints = emptyMap())

        assertTrue(isObservationStale(observation, current, listOf(guard)))
    }
}
