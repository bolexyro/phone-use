package com.phonecontrol.assistant.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevBridgeServerTest {
    @Test
    fun `full access returns capability without enumerating apps`() {
        val response = buildAllowedAppsResponse(
            requestId = "request-1",
            fullAccess = true,
            allowedPackages = setOf("com.example.spotify", "com.example.mail"),
        )

        assertTrue(response.getBoolean("fullAccess"))
        assertEquals("full_access", response.getString("accessMode"))
        assertEquals(
            "Full Access is enabled. You can use any launchable app on the phone.",
            response.getString("message"),
        )
        assertFalse(response.has("allowedPackages"))
        assertFalse(response.has("count"))
    }

    @Test
    fun `restricted access returns the sorted explicit allowlist`() {
        val response = buildAllowedAppsResponse(
            requestId = "request-2",
            fullAccess = false,
            allowedPackages = setOf("com.example.zed", "com.example.alfred"),
        )

        assertFalse(response.getBoolean("fullAccess"))
        assertEquals("allowlist", response.getString("accessMode"))
        assertEquals(2, response.getInt("count"))
        assertEquals(
            "[\"com.example.alfred\",\"com.example.zed\"]",
            response.getJSONArray("allowedPackages").toString(),
        )
        assertFalse(response.has("message"))
    }
}
