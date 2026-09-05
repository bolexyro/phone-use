package com.phonecontrol.assistant.shizuku

import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.StaleObservationReasonCode
import org.junit.Assert.assertEquals
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

    @Test
    fun `reports structural and guarded freshness reasons with before and after values`() {
        val current = observation.copy(
            id = "obs-2",
            packageName = "com.example.other",
            activityName = "SearchActivity",
            displayId = 1,
            rotation = 1,
            width = 1440,
            height = 3200,
            guardFingerprints = mapOf(guard to "guard-after"),
        )

        val reasons = observationStaleReasons(observation, current, listOf(guard))

        assertEquals(
            listOf(
                StaleObservationReasonCode.PACKAGE_CHANGED,
                StaleObservationReasonCode.ACTIVITY_CHANGED,
                StaleObservationReasonCode.DISPLAY_CHANGED,
                StaleObservationReasonCode.ROTATION_CHANGED,
                StaleObservationReasonCode.DISPLAY_SIZE_CHANGED,
                StaleObservationReasonCode.GUARD_REGION_CHANGED,
            ),
            reasons.map { it.code },
        )
        assertEquals("com.example.shop", reasons[0].approved)
        assertEquals("com.example.other", reasons[0].current)
        assertEquals("guard-before", reasons.last().approved)
        assertEquals("guard-after", reasons.last().current)
        assertEquals(guard, reasons.last().guardRegion)
    }
}
