package com.phonecontrol.assistant.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

data class ShizukuStatus(
    val binderAvailable: Boolean = false,
    val permissionGranted: Boolean = false,
    val apiVersion: Int? = null,
    val privilegedApiReady: Boolean = false,
    val message: String = "Checking Shizuku…",
)

/**
 * Owns the official Shizuku client lifecycle. This class detects capability;
 * it deliberately does not run shell commands or claim that input injection
 * works on a particular phone until that path is verified.
 */
class ShizukuController {
    private val _status = MutableStateFlow(ShizukuStatus())
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }
    private var started = false

    fun start() {
        if (started) return
        started = true
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    fun refresh() {
        val binderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAvailable) {
            _status.value = ShizukuStatus(message = "Shizuku is not running or is unavailable.")
            return
        }

        val apiVersion = runCatching { Shizuku.getVersion() }.getOrNull()
        val preV11 = runCatching { Shizuku.isPreV11() }.getOrDefault(true)
        val permissionGranted = if (preV11) {
            false
        } else {
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)
        }
        val ready = !preV11 && permissionGranted
        _status.value = ShizukuStatus(
            binderAvailable = true,
            permissionGranted = permissionGranted,
            apiVersion = apiVersion,
            privilegedApiReady = ready,
            message = when {
                preV11 -> "This Shizuku service is too old; API v11+ is required."
                permissionGranted -> "Shizuku is ready. Input execution remains unverified."
                else -> "Shizuku is running; permission is required."
            },
        )
    }

    fun requestPermission(): Boolean {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return false
        return runCatching {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val REQUEST_CODE = 1001
    }
}
