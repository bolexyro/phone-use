package com.phonecontrol.assistant.session

import com.phonecontrol.assistant.domain.ActivityEvent
import com.phonecontrol.assistant.domain.ActivityEventKind
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.policy.PolicyContext
import com.phonecontrol.assistant.policy.PolicyDecision
import com.phonecontrol.assistant.policy.PolicyEngine
import com.phonecontrol.assistant.shizuku.PhoneActionTransport
import com.phonecontrol.assistant.shizuku.TransportResult
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SessionState {
    data object Idle : SessionState

    data class Running(
        val sessionId: String,
        val request: String,
        val currentPurpose: String,
        val startedAtEpochMs: Long,
    ) : SessionState

    data class Paused(
        val sessionId: String,
        val request: String,
        val currentPurpose: String,
        val startedAtEpochMs: Long,
    ) : SessionState

    data class Stopped(
        val sessionId: String,
        val reason: String,
    ) : SessionState

    data class Completed(
        val sessionId: String,
        val message: String,
    ) : SessionState
}

sealed interface ActionExecutionResult {
    data object SessionNotRunning : ActionExecutionResult
    data class ConfirmationRequired(val category: String, val message: String) : ActionExecutionResult
    data class PolicyRejected(val message: String) : ActionExecutionResult
    data class TransportFinished(val result: TransportResult) : ActionExecutionResult
}

/**
 * A phone-originated request waiting for the desktop Codex companion to
 * accept it. The session id makes the handoff idempotent across polling and
 * prevents a stale desktop response from being applied to a newer session.
 */
data class PendingRequest(
    val sessionId: String,
    val request: String,
)

/**
 * Process-local session state shared by the Compose activity, foreground
 * service, and desktop Codex bridge. The phone owns the handoff and all typed
 * observation/action policy decisions.
 */
class SessionCoordinator(
    private val enabledPackagesProvider: () -> Set<String>,
    private val policyEngine: PolicyEngine,
    private val transport: PhoneActionTransport,
) {
    private val lock = Any()
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    private val _events = MutableStateFlow<List<ActivityEvent>>(emptyList())
    private var sessionJob: Job? = null
    private var claimedRequestSessionId: String? = null

    val state: StateFlow<SessionState> = _state.asStateFlow()
    val events: StateFlow<List<ActivityEvent>> = _events.asStateFlow()

    fun start(request: String): Boolean = synchronized(lock) {
        if (request.isBlank() || _state.value.isActive) return false

        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        claimedRequestSessionId = null
        _state.value = SessionState.Running(
            sessionId = sessionId,
            request = request.trim(),
            currentPurpose = "Preparing request",
            startedAtEpochMs = now,
        )
        sessionJob?.cancel()
        sessionJob = SupervisorJob()
        appendEvent(
            ActivityEventKind.SESSION_STARTED,
            "Request accepted. Waiting for the desktop Codex bridge.",
            sessionId = sessionId,
        )
        true
    }

    /** Return the active phone request until a desktop companion claims it. */
    fun pendingRequest(): PendingRequest? = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return@synchronized null
        if (claimedRequestSessionId == running.sessionId) return@synchronized null
        PendingRequest(running.sessionId, running.request)
    }

    /**
     * Atomically claim the current phone request. Pollers can pass the session
     * id they observed so a delayed claim cannot attach to a newer request.
     */
    fun claimRequest(expectedSessionId: String? = null): PendingRequest? = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return@synchronized null
        if (expectedSessionId != null && expectedSessionId != running.sessionId) {
            return@synchronized null
        }
        if (claimedRequestSessionId == running.sessionId) return@synchronized null
        claimedRequestSessionId = running.sessionId
        _state.value = running.copy(currentPurpose = "Codex is planning")
        appendEvent(
            ActivityEventKind.SYSTEM,
            "Desktop Codex companion claimed the request.",
            sessionId = running.sessionId,
        )
        PendingRequest(running.sessionId, running.request)
    }

    /** Release a claim after a desktop-side failure so the user can retry. */
    fun releaseRequest(sessionId: String): Boolean = synchronized(lock) {
        if (claimedRequestSessionId != sessionId) return@synchronized false
        val activeSessionId = _state.value.sessionIdOrNull
        if (activeSessionId != sessionId) {
            claimedRequestSessionId = null
            return@synchronized false
        }
        claimedRequestSessionId = null
        if (_state.value is SessionState.Running) {
            val running = _state.value as SessionState.Running
            _state.value = running.copy(currentPurpose = "Waiting for desktop Codex bridge")
        }
        appendEvent(
            ActivityEventKind.SYSTEM,
            "Desktop Codex companion released the request; waiting for retry.",
            sessionId = sessionId,
        )
        true
    }

    fun pause(): Boolean = synchronized(lock) {
        val running = _state.value as? SessionState.Running ?: return false
        _state.value = SessionState.Paused(
            sessionId = running.sessionId,
            request = running.request,
            currentPurpose = running.currentPurpose,
            startedAtEpochMs = running.startedAtEpochMs,
        )
        appendEvent(ActivityEventKind.SESSION_PAUSED, "Session paused.", running.sessionId)
        true
    }

    fun resume(): Boolean = synchronized(lock) {
        val paused = _state.value as? SessionState.Paused ?: return false
        _state.value = SessionState.Running(
            sessionId = paused.sessionId,
            request = paused.request,
            currentPurpose = paused.currentPurpose,
            startedAtEpochMs = paused.startedAtEpochMs,
        )
        appendEvent(ActivityEventKind.SESSION_RESUMED, "Session resumed.", paused.sessionId)
        true
    }

    fun togglePause(): Boolean = when (_state.value) {
        is SessionState.Running -> pause()
        is SessionState.Paused -> resume()
        else -> false
    }

    fun stop(reason: String = "Stopped by the user."): Boolean = synchronized(lock) {
        val sessionId = _state.value.sessionIdOrNull ?: return false
        sessionJob?.cancel()
        sessionJob = null
        claimedRequestSessionId = null
        _state.value = SessionState.Stopped(sessionId, reason)
        appendEvent(ActivityEventKind.SESSION_STOPPED, reason, sessionId)
        true
    }

    fun complete(message: String = "Session completed."): Boolean = synchronized(lock) {
        val sessionId = _state.value.sessionIdOrNull ?: return false
        sessionJob = null
        claimedRequestSessionId = null
        _state.value = SessionState.Completed(sessionId, message)
        appendEvent(ActivityEventKind.SESSION_COMPLETED, message, sessionId)
        true
    }

    fun setCurrentPurpose(purpose: String): Boolean = synchronized(lock) {
        val current = _state.value
        val updated = when (current) {
            is SessionState.Running -> current.copy(currentPurpose = purpose)
            is SessionState.Paused -> current.copy(currentPurpose = purpose)
            else -> return false
        }
        _state.value = updated
        true
    }

    /**
     * Policy and transport integration point for a future Codex bridge. A
     * sensitive action stops at ConfirmationRequired; there is no model-side
     * bypass flag and no implicit approval in this method.
     */
    suspend fun executeAction(
        action: PhoneAction,
        observation: ObservationSnapshot?,
    ): ActionExecutionResult {
        val running = _state.value as? SessionState.Running
            ?: return ActionExecutionResult.SessionNotRunning
        setCurrentPurpose(action.metadata.purpose)
        appendEvent(
            ActivityEventKind.ACTION_PROPOSED,
            action.metadata.purpose,
            sessionId = running.sessionId,
            actionType = action.type,
            purpose = action.metadata.purpose,
            observationId = action.metadata.observationId,
        )

        val decision = policyEngine.evaluate(
            action,
            PolicyContext(
                enabledPackages = enabledPackagesProvider(),
                foregroundPackage = observation?.packageName,
                currentObservationId = observation?.id,
            ),
        )
        when (decision) {
            PolicyDecision.Allowed -> Unit
            is PolicyDecision.RequiresConfirmation -> {
                appendEvent(
                    ActivityEventKind.CONFIRMATION_REQUIRED,
                    decision.message,
                    sessionId = running.sessionId,
                    actionType = action.type,
                    purpose = action.metadata.purpose,
                    observationId = action.metadata.observationId,
                )
                setCurrentPurpose("Waiting for confirmation")
                return ActionExecutionResult.ConfirmationRequired(
                    category = decision.category.name,
                    message = decision.message,
                )
            }

            is PolicyDecision.Denied -> {
                appendEvent(
                    ActivityEventKind.ACTION_FAILED,
                    decision.message,
                    sessionId = running.sessionId,
                    actionType = action.type,
                    purpose = action.metadata.purpose,
                    observationId = action.metadata.observationId,
                )
                return ActionExecutionResult.PolicyRejected(decision.message)
            }
        }

        appendEvent(
            ActivityEventKind.ACTION_STARTED,
            "Executing ${action.type.name.lowercase().replace('_', ' ')}",
            sessionId = running.sessionId,
            actionType = action.type,
            purpose = action.metadata.purpose,
            observationId = action.metadata.observationId,
        )
        val result = transport.execute(action, observation)
        val eventKind = if (result is TransportResult.Succeeded) {
            ActivityEventKind.ACTION_SUCCEEDED
        } else {
            ActivityEventKind.ACTION_FAILED
        }
        appendEvent(
            eventKind,
            transportMessage(result),
            sessionId = running.sessionId,
            actionType = action.type,
            purpose = action.metadata.purpose,
            observationId = action.metadata.observationId,
        )
        return ActionExecutionResult.TransportFinished(result)
    }

    fun close() {
        sessionJob?.cancel()
        sessionJob = null
    }

    private fun appendEvent(
        kind: ActivityEventKind,
        message: String,
        sessionId: String? = _state.value.sessionIdOrNull,
        actionType: com.phonecontrol.assistant.domain.ActionType? = null,
        purpose: String? = null,
        observationId: String? = null,
    ) {
        val event = ActivityEvent(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            timestampEpochMs = System.currentTimeMillis(),
            kind = kind,
            message = message,
            actionType = actionType,
            purpose = purpose,
            observationId = observationId,
        )
        _events.value = (_events.value + event).takeLast(MAX_EVENTS)
    }

    private fun transportMessage(result: TransportResult): String = when (result) {
        is TransportResult.Rejected -> result.message
        is TransportResult.Unsupported -> result.message
        is TransportResult.Succeeded -> result.message
    }

    private companion object {
        const val MAX_EVENTS = 100
    }
}

private val SessionState.isActive: Boolean
    get() = this is SessionState.Running || this is SessionState.Paused

private val SessionState.sessionIdOrNull: String?
    get() = when (this) {
        is SessionState.Idle -> null
        is SessionState.Running -> sessionId
        is SessionState.Paused -> sessionId
        is SessionState.Stopped -> sessionId
        is SessionState.Completed -> sessionId
    }
