package com.phonecontrol.assistant.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

data class InstalledUserApp(
    val packageName: String,
    val label: String,
)

/** Lists launchable packages available to the user on this device. */
class InstalledAppsRepository(private val context: Context) {
    fun listLaunchableUserApps(): List<InstalledUserApp> = listLaunchableApps(includeSystemApps = false)

    fun listLaunchableApps(): List<InstalledUserApp> = listLaunchableApps(includeSystemApps = true)

    private fun listLaunchableApps(includeSystemApps: Boolean): List<InstalledUserApp> {
        val packageManager = context.packageManager
        val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launchIntent, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { resolveInfo ->
                val applicationInfo = resolveInfo.activityInfo?.applicationInfo ?: return@mapNotNull null
                val isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (!includeSystemApps && isSystemApp) return@mapNotNull null
                val packageName = applicationInfo.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                val label = resolveInfo.loadLabel(packageManager).toString().ifBlank { packageName }
                InstalledUserApp(packageName = packageName, label = label)
            }
            .distinctBy { it.packageName }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }
}
