package com.phonecontrol.assistant.domain

/** Stable machine-readable reasons for refusing an action from an old screen. */
enum class StaleObservationReasonCode {
    GUARD_REGION_CHANGED,
    ROTATION_CHANGED,
    DISPLAY_CHANGED,
    DISPLAY_SIZE_CHANGED,
    PACKAGE_CHANGED,
    ACTIVITY_CHANGED,
    OBSERVATION_REPLACED,
}

/** One independently detected freshness delta. */
data class StaleObservationReason(
    val code: StaleObservationReasonCode,
    val approved: Any? = null,
    val current: Any? = null,
    val guardRegion: GuardRegion? = null,
)

/** Diagnostic context attached to a stale-observation rejection. */
data class StaleObservationDiagnostics(
    val approvedObservationId: String,
    val currentObservationId: String? = null,
    val reasons: List<StaleObservationReason>,
)

/** JSON-friendly value used when reporting display dimensions. */
data class ObservationSize(
    val width: Int,
    val height: Int,
)
