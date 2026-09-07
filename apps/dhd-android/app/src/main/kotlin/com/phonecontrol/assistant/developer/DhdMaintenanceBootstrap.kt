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
        if (client.isReady()) return

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
            if (client.isReady()) return
            delay(100)
        }
        throw IOException(
            "DHD maintenance service did not start. Check ${maintenanceLogPath()} after retrying.",
        )
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
