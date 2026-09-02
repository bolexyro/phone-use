package com.phonecontrol.assistant.policy

import com.phonecontrol.assistant.domain.ActionMetadata
import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.OpenAppAction
import com.phonecontrol.assistant.domain.TapAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyEngineTest {
    private val engine = PolicyEngine(enforceObservationFreshness = true)
    private val relaxedEngine = PolicyEngine(enforceObservationFreshness = false)
    private val observation = ObservationSnapshot(
        id = "obs-1",
        packageName = "com.example.shop",
        activityName = "ShopActivity",
        displayId = 0,
        rotation = 0,
        width = 1080,
        height = 2400,
        screenshotFingerprint = "fingerprint",
    )

    @Test
    fun `allows typed action for enabled foreground app and matching observation`() {
        val decision = engine.evaluate(
            action = TapAction(
                x = 500,
                y = 900,
                metadata = metadata("Select restaurant"),
            ),
            context = context(),
        )

        assertEquals(PolicyDecision.Allowed, decision)
    }

    @Test
    fun `denies action when foreground app is not allowlisted`() {
        val decision = engine.evaluate(
            TapAction(500, 900, metadata("Select restaurant")),
            context(enabledPackages = emptySet()),
        )

        assertTrue(decision is PolicyDecision.Denied)
        assertEquals(DenialCode.APP_NOT_ALLOWED, (decision as PolicyDecision.Denied).code)
    }

    @Test
    fun `denies stale observation`() {
        val decision = engine.evaluate(
            TapAction(500, 900, metadata("Select restaurant", observationId = "obs-old")),
            context(),
        )

        assertTrue(decision is PolicyDecision.Denied)
        assertEquals(DenialCode.STALE_OBSERVATION, (decision as PolicyDecision.Denied).code)
    }

    @Test
    fun `rejects guarded tap when coordinate is outside every guard`() {
        val decision = engine.evaluate(
            TapAction(
                500,
                900,
                metadata("Select restaurant").copy(
                    guardRegions = listOf(GuardRegion(0, 0, 100, 100)),
                ),
            ),
            context(),
        )

        assertTrue(decision is PolicyDecision.Denied)
        assertEquals(DenialCode.INVALID_GUARD_REGIONS, (decision as PolicyDecision.Denied).code)
    }

    @Test
    fun `open app checks its package allowlist`() {
        val decision = engine.evaluate(
            OpenAppAction(
                packageName = "com.example.shop",
                metadata = metadata("Open shop"),
            ),
            context(foregroundPackage = null),
        )

        assertEquals(PolicyDecision.Allowed, decision)
    }

    @Test
    fun `allows action on non-allowlisted app when full access is enabled`() {
        val decision = engine.evaluate(
            TapAction(500, 900, metadata("Select restaurant")),
            context(
                enabledPackages = emptySet(),
                foregroundPackage = "com.unlisted.app",
            ).copy(fullAccess = true),
        )

        assertEquals(PolicyDecision.Allowed, decision)
    }

    @Test
    fun `allows open app action for non-allowlisted package when full access is enabled`() {
        val decision = engine.evaluate(
            OpenAppAction(
                packageName = "com.unlisted.app",
                metadata = metadata("Open unlisted app"),
            ),
            context(
                enabledPackages = emptySet(),
                foregroundPackage = null,
            ).copy(fullAccess = true),
        )

        assertEquals(PolicyDecision.Allowed, decision)
    }

    @Test
    fun `allows action without observation when freshness is disabled`() {
        val decision = relaxedEngine.evaluate(
            TapAction(500, 900, metadata("Select restaurant", observationId = "")),
            context().copy(currentObservationId = null),
        )

        assertEquals(PolicyDecision.Allowed, decision)
    }

    private fun metadata(purpose: String, observationId: String = "obs-1") = ActionMetadata(
        purpose = purpose,
        observationId = observationId,
        targetDescription = "visible target",
    )

    private fun context(
        enabledPackages: Set<String> = setOf("com.example.shop"),
        foregroundPackage: String? = "com.example.shop",
    ) = PolicyContext(
        enabledPackages = enabledPackages,
        foregroundPackage = foregroundPackage,
        currentObservationId = observation.id,
    )
}
