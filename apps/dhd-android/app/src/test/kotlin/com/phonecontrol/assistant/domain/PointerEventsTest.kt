package com.phonecontrol.assistant.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class PointerEventsTest {
    @Test
    fun `scroll gestures use the same center and distance for every direction`() {
        val up = calculateTaskScrollGesture(720, 1560, ScrollDirection.UP, ScrollAmount.MEDIUM)
        val down = calculateTaskScrollGesture(720, 1560, ScrollDirection.DOWN, ScrollAmount.MEDIUM)
        val left = calculateTaskScrollGesture(720, 1560, ScrollDirection.LEFT, ScrollAmount.MEDIUM)
        val right = calculateTaskScrollGesture(720, 1560, ScrollDirection.RIGHT, ScrollAmount.MEDIUM)

        assertEquals(TaskScrollGesture(360, 1107, 360, 453), up)
        assertEquals(TaskScrollGesture(360, 453, 360, 1107), down)
        assertEquals(TaskScrollGesture(511, 780, 209, 780), left)
        assertEquals(TaskScrollGesture(209, 780, 511, 780), right)
    }

    @Test
    fun `scroll gestures are centered on the requested display coordinate`() {
        val gesture = calculateTaskScrollGesture(
            width = 720,
            height = 1560,
            direction = ScrollDirection.DOWN,
            amount = ScrollAmount.SMALL,
            centerX = 180,
            centerY = 600,
        )

        assertEquals(TaskScrollGesture(180, 429, 180, 771), gesture)
    }
}
