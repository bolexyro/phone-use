package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.apps.InstalledUserApp
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
        assertTrue(response.getBoolean("canListAllApps"))
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

    @Test
    fun `full access returns all apps only when explicitly requested`() {
        val response = buildAllowedAppsResponse(
            requestId = "request-3",
            fullAccess = true,
            allowedPackages = emptySet(),
            includeAll = true,
            apps = listOf(
                InstalledUserApp(packageName = "com.example.zed", label = "Zed"),
                InstalledUserApp(packageName = "com.example.alfred", label = "Alfred"),
            ),
        )

        assertTrue(response.getBoolean("fullAccess"))
        assertTrue(response.getBoolean("canListAllApps"))
        assertEquals(2, response.getInt("count"))
        assertEquals(
            "[{\"appLabel\":\"Zed\",\"packageName\":\"com.example.zed\"},{\"appLabel\":\"Alfred\",\"packageName\":\"com.example.alfred\"}]",
            response.getJSONArray("apps").toString(),
        )
        assertFalse(response.has("icon"))
    }

    @Test
    fun `browse response contains only app labels and package names`() {
        val response = buildBrowseAppsResponse(
            requestId = "request-4",
            query = "alfred",
            fullAccess = true,
            apps = listOf(InstalledUserApp(packageName = "com.example.alfred", label = "Alfred")),
            truncated = false,
        )

        assertEquals("browse_apps", response.getString("type"))
        assertEquals("alfred", response.getString("query"))
        assertEquals("Alfred", response.getJSONArray("apps").getJSONObject(0).getString("appLabel"))
        assertEquals("com.example.alfred", response.getJSONArray("apps").getJSONObject(0).getString("packageName"))
        assertFalse(response.has("icon"))
    }
}
