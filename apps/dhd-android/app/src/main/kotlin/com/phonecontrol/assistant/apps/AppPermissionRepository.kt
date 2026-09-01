package com.phonecontrol.assistant.apps

import android.content.Context

/** Persists the explicit per-package allowlist; no global enable-all setting exists. */
class AppPermissionRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun enabledPackages(): Set<String> = preferences.getStringSet(KEY_ENABLED_PACKAGES, emptySet())
        ?.toSet()
        ?: emptySet()

    fun isEnabled(packageName: String): Boolean = packageName in enabledPackages()

    fun setEnabled(packageName: String, enabled: Boolean) {
        val next = enabledPackages().toMutableSet()
        if (enabled) next += packageName else next -= packageName
        preferences.edit().putStringSet(KEY_ENABLED_PACKAGES, next).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_control_permissions"
        const val KEY_ENABLED_PACKAGES = "enabled_packages"
    }
}
