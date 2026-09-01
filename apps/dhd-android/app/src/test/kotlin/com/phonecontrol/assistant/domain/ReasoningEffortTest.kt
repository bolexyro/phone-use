package com.phonecontrol.assistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningEffortTest {
    @Test
    fun `efforts stay ordered from light to max`() {
        assertEquals(
            listOf("low", "medium", "high", "xhigh", "max"),
            ReasoningEffort.entries.map(ReasoningEffort::codexValue),
        )
    }

    @Test
    fun `display labels map to their Codex values`() {
        assertEquals("Light", ReasoningEffort.fromStorage("light")?.label)
        assertEquals("xhigh", ReasoningEffort.fromStorage("extra_high")?.codexValue)
        assertEquals("Max", ReasoningEffort.fromCodexValue("max")?.label)
    }

    @Test
    fun `unknown Codex values do not become selectable efforts`() {
        assertNull(ReasoningEffort.fromCodexValue("instant"))
    }
}
