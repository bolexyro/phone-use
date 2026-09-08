package com.phonecontrol.assistant.developer

import android.content.Context
import android.content.SharedPreferences
import java.io.IOException
import kotlinx.coroutines.delay

/**
 * Starts the DHD shell-UID daemon using the same detached app_process command
 * shape used by Shizuku's Apache-2.0 ServiceStarter.
 *
 * See: https://github.com/RikkaApps/Shizuku/blob/master/starter/src/main/java/moe/shizuku/starter/ServiceStarter.java
 */
internal class DhdMaintenanceBootstrap(
    private val context: Context,
    private val preferences: SharedPreferences,
) {
    private val port: Int by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val existing = preferences.getInt(KEY_MAINTENANCE_PORT, -1)
        if (existing in 1024..65535) {
            existing
        } else {
            DhdMaintenanceClient.newPort().also { generated ->
                preferences.edit().putInt(KEY_MAINTENANCE_PORT, generated).apply()
            }
        }
    }

    private val token: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val existing = preferences.getString(KEY_MAINTENANCE_TOKEN, null)
        if (!existing.isNullOrBlank()) {
            existing
        } else {
            DhdMaintenanceClient.newToken().also { generated ->
                preferences.edit().putString(KEY_MAINTENANCE_TOKEN, generated).apply()
            }
        }
    }

    fun client(): DhdMaintenanceClient = DhdMaintenanceClient(port = port, token = token)

    suspend fun ensureStarted(adb: DhdAdbClient) {
        val client = client()
        if (client.isCompatible()) return
        // A previous APK can leave an older daemon bound to the persisted
        // port. `true` alone is not a capability check: stop that exact
        // process before starting the new daemon so display commands cannot
        // silently fall back to an old implementation.
        if (client.isReady()) {
            stopExistingDaemon(adb)
        }

        val result = adb.shellV2(
            buildDhdMaintenanceStartCommand(
                apkPath = context.applicationInfo.sourceDir,
                port = port,
                token = token,
                logPath = maintenanceLogPath(),
            ),
        )
        if (result.exitCode != 0) {
            throw IOException(
                "DHD maintenance bootstrap failed: ${result.stderr.ifBlank { "exit ${result.exitCode}" }}",
            )
        }

        repeat(20) {
            if (client.isCompatible()) return
            delay(100)
        }
        throw IOException(
            "DHD maintenance service with native display support did not start. " +
                "Check ${maintenanceLogPath()} after retrying.",
        )
    }

    private suspend fun stopExistingDaemon(adb: DhdAdbClient) {
        val pidResult = runCatching { adb.shellV2("pidof dhd_maintenance") }.getOrNull() ?: return
        val pids = Regex("\\b\\d+\\b")
            .findAll(String(pidResult.stdout, Charsets.UTF_8))
            .map { it.value }
            .distinct()
            .toList()
        pids.forEach { pid ->
            // The PID is parsed as decimal digits from pidof output; it is
            // never assembled from user or model text.
            runCatching { adb.shellV2("kill -TERM $pid") }
        }
        repeat(10) {
            if (!client().isReady()) return
            delay(100)
        }
    }

    private fun maintenanceLogPath(): String =
        context.getExternalFilesDir(null)
            ?.resolve("dhd-maintenance.log")
            ?.absolutePath
            ?: "/data/local/tmp/dhd-maintenance.log"

    private companion object {
        const val KEY_MAINTENANCE_PORT = "maintenance_port"
        const val KEY_MAINTENANCE_TOKEN = "maintenance_token"
    }
}

/** Build the Shizuku-style detached app_process command without user input. */
internal fun buildDhdMaintenanceStartCommand(
    apkPath: String,
    port: Int,
    token: String,
    logPath: String,
): String {
    require(port in 1024..65535) { "Maintenance port is invalid." }
    val apk = quoteDhdAdbShellArgument(apkPath)
    val processName = quoteDhdAdbShellArgument("dhd_maintenance")
    val daemonClass = quoteDhdAdbShellArgument(DhdMaintenanceDaemon::class.java.name)
    val portArgument = quoteDhdAdbShellArgument("--port=$port")
    val tokenArgument = quoteDhdAdbShellArgument("--token=$token")
    val log = quoteDhdAdbShellArgument(logPath)
    return "(CLASSPATH=$apk /system/bin/setsid /system/bin/app_process /system/bin " +
        "--nice-name=$processName $daemonClass $portArgument $tokenArgument) " +
        "</dev/null >$log 2>&1 &"
}
