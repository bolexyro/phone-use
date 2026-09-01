package com.phonecontrol.assistant.shizuku

import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/** Result of one fixed, typed command dispatched through the Shizuku shell. */
data class ShizukuProcessResult(
    val exitCode: Int?,
    val stdout: ByteArray,
    val stderr: String,
    val timedOut: Boolean = false,
)

/**
 * Small boundary around Shizuku's legacy process API.
 *
 * Shizuku 13 still contains `newProcess`, but it is private and deprecated in
 * preparation for API 14 removal. Keeping the reflection in one file means
 * no provider or model can smuggle an arbitrary shell command into the app.
 * The action transport below constructs the command argv itself and this
 * runner only executes that already-validated argv.
 */
class ShizukuProcessRunner(
    private val controller: ShizukuController,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    suspend fun run(command: List<String>): ShizukuProcessResult = withContext(Dispatchers.IO) {
        require(command.isNotEmpty()) { "A Shizuku command must not be empty." }
        val status = controller.status.value
        if (!status.binderAvailable) {
            return@withContext ShizukuProcessResult(
                exitCode = null,
                stdout = ByteArray(0),
                stderr = "Shizuku is unavailable.",
            )
        }
        if (!status.permissionGranted) {
            return@withContext ShizukuProcessResult(
                exitCode = null,
                stdout = ByteArray(0),
                stderr = "Shizuku permission is required.",
            )
        }

        val process = try {
            newProcess(command.toTypedArray())
        } catch (error: Throwable) {
            return@withContext ShizukuProcessResult(
                exitCode = null,
                stdout = ByteArray(0),
                stderr = "Unable to start the typed Shizuku process: ${rootMessage(error)}",
            )
        }

        try {
            coroutineScope {
                val stdout = async(Dispatchers.IO) { process.inputStream.use { it.readBytes() } }
                val stderr = async(Dispatchers.IO) {
                    process.errorStream.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                val completed = if (process is ShizukuRemoteProcess) {
                    process.waitForTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                } else {
                    process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                }
                if (!completed) {
                    process.destroy()
                    // Do not wait indefinitely for a broken shell process. The
                    // readers are cancelled when this coroutine is cancelled.
                    return@coroutineScope ShizukuProcessResult(
                        exitCode = null,
                        stdout = ByteArray(0),
                        stderr = "The Shizuku command timed out after ${timeoutMs}ms.",
                        timedOut = true,
                    )
                }
                ShizukuProcessResult(
                    // ShizukuRemoteProcess can report a completed timed wait
                    // while its remote exitValue call still races. A direct
                    // waitFor asks the remote process for the authoritative
                    // status and returns immediately after completion.
                    exitCode = if (process is ShizukuRemoteProcess) {
                        process.waitFor()
                    } else {
                        process.waitFor()
                    },
                    stdout = stdout.await(),
                    stderr = stderr.await().trim(),
                )
            }
        } catch (error: InterruptedException) {
            process.destroy()
            Thread.currentThread().interrupt()
            ShizukuProcessResult(
                exitCode = null,
                stdout = ByteArray(0),
                stderr = "The Shizuku command was interrupted.",
            )
        } catch (error: IOException) {
            process.destroy()
            ShizukuProcessResult(
                exitCode = null,
                stdout = ByteArray(0),
                stderr = "The Shizuku command could not be read: ${rootMessage(error)}",
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun newProcess(command: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
        return method.invoke(null, command, null, null) as Process
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L

        fun rootMessage(error: Throwable): String {
            val cause = when (error) {
                is InvocationTargetException -> error.targetException
                else -> error
            }
            return cause.message ?: cause::class.java.simpleName
        }
    }
}
