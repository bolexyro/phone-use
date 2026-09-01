package com.phonecontrol.assistant.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionModelsTest {
    @Test
    fun `guard region contains only its interior coordinates`() {
        val region = GuardRegion(left = 420, top = 840, right = 680, bottom = 960)

        assertTrue(region.contains(500, 900))
        assertFalse(region.contains(680, 900))
        assertFalse(region.contains(500, 960))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `guard region rejects inverted geometry`() {
        GuardRegion(left = 680, top = 840, right = 420, bottom = 960)
    }
}
