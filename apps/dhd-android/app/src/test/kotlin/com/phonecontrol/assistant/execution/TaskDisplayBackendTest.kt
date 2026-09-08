package com.phonecontrol.assistant.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDisplayBackendTest {
    @Test
    fun `default spec keeps the fixed task geometry and app density override`() {
        val spec = TaskDisplaySpec()

        assertEquals(720, spec.width)
        assertEquals(1_560, spec.height)
        assertEquals(420, spec.densityDpi)
        assertEquals(320, spec.appDensityDpi)
    }

    @Test
    fun `display sessions reject the default display`() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskDisplaySession(
                sessionKey = "run-1",
                taskId = "run-1@0",
                displayId = 0,
                geometry = TaskDisplayGeometry(720, 1_560, 420, 0),
            )
        }
    }

    @Test
    fun `terminalization starts retention at the first terminal timestamp`() {
        val record = TaskDisplayRecord(
            sessionKey = "run-1",
            taskId = "run-1@7",
            packageName = "com.example.app",
            displayId = 7,
            width = 720,
            height = 1_560,
            densityDpi = 420,
            rotation = 0,
            status = TaskDisplayStatus.RUNNING,
            createdAtEpochMs = 1_000L,
            lastPurpose = "Preparing request",
        )

        val completed = record.terminalized(
            status = TaskDisplayStatus.COMPLETED,
            terminalAtEpochMs = 5_000L,
            retentionMs = 30 * 60 * 1_000L,
        )
        val retried = completed.terminalized(
            status = TaskDisplayStatus.STOPPED,
            terminalAtEpochMs = 99_000L,
            retentionMs = 30 * 60 * 1_000L,
        )

        assertEquals(TaskDisplayStatus.COMPLETED, completed.status)
        assertEquals(5_000L, completed.terminalAtEpochMs)
        assertEquals(1_805_000L, completed.expiresAtEpochMs)
        assertEquals("Task complete", completed.lastPurpose)
        assertEquals(5_000L, retried.terminalAtEpochMs)
        assertEquals(1_805_000L, retried.expiresAtEpochMs)
    }

    @Test
    fun `terminalization sanitizes retained error text`() {
        val record = TaskDisplayRecord(
            sessionKey = "run-2",
            taskId = "run-2@8",
            packageName = "com.example.app",
            displayId = 8,
            width = 720,
            height = 1_560,
            densityDpi = 420,
            rotation = 0,
            status = TaskDisplayStatus.RUNNING,
            createdAtEpochMs = 1L,
        )

        val failed = record.terminalized(
            status = TaskDisplayStatus.FAILED,
            terminalAtEpochMs = 2L,
            retentionMs = 1_000L,
            error = "  launch failed  ",
        )

        assertEquals("launch failed", failed.error)
        assertNotNull(failed.expiresAtEpochMs)
        assertTrue(failed.status.isTerminal)
    }
}
