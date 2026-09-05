export const PHONE_CONTROL_ERROR_CODES = [
  "POLICY_NOT_FOUND",
  "POLICY_INVALID",
  "ADB_NOT_FOUND",
  "SCRCPY_NOT_FOUND",
  "VIEWER_UNSUPPORTED_PLATFORM",
  "VIEWER_START_FAILED",
  "ADB_COMMAND_FAILED",
  "ADB_TIMEOUT",
  "DEVICE_NOT_FOUND",
  "NO_AUTHORIZED_DEVICE",
  "MULTIPLE_AUTHORIZED_DEVICES",
  "FORBIDDEN_APP",
  "FOREGROUND_UNAVAILABLE",
  "INVALID_OBSERVATION",
  "STALE_OBSERVATION",
  "ELEMENT_NOT_FOUND",
  "ELEMENT_NO_BOUNDS",
  "INVALID_COORDINATE",
  "INVALID_ACTION",
  "APP_LAUNCH_FAILED",
  "MULTI_INSTANCE_UNSUPPORTED",
  "VIRTUAL_DISPLAY_UNSUPPORTED",
  "VIRTUAL_DISPLAY_FAILED",
  "OBSERVATION_FAILED",
  "WAIT_TIMEOUT",
  "NO_ACTIVE_SESSION",
  "INTERNAL_ERROR"
] as const;

export type PhoneControlErrorCode =
  (typeof PHONE_CONTROL_ERROR_CODES)[number];

export type ErrorDetails = Readonly<Record<string, unknown>>;

/** Machine-readable causes of a rejected action based on an old observation. */
export const STALE_OBSERVATION_REASON_CODES = [
  "GUARD_REGION_CHANGED",
  "ROTATION_CHANGED",
  "DISPLAY_CHANGED",
  "DISPLAY_SIZE_CHANGED",
  "PACKAGE_CHANGED",
  "ACTIVITY_CHANGED",
  "OBSERVATION_REPLACED",
  "DEVICE_CHANGED",
  "MODE_CHANGED",
  "SCREENSHOT_CHANGED",
  "UI_TREE_CHANGED",
  "TARGET_CHANGED",
  "TARGET_OBSCURED"
] as const;

export type StaleObservationReasonCode =
  (typeof STALE_OBSERVATION_REASON_CODES)[number];

export interface StaleObservationReason {
  code: StaleObservationReasonCode;
  approved?: unknown;
  current?: unknown;
  guardRegion?: {
    left: number;
    top: number;
    right: number;
    bottom: number;
  };
  field?: string;
}

export interface StaleObservationDiagnostics {
  approvedObservationId?: string;
  currentObservationId?: string;
  /** Legacy comparison field names retained for machine consumers. */
  changed?: readonly string[];
  reasons: readonly StaleObservationReason[];
}

export interface MachineError {
  ok: false;
  error: {
    code: PhoneControlErrorCode;
    message: string;
    details: ErrorDetails;
  };
}

export class PhoneControlError extends Error {
  public readonly name = "PhoneControlError";

  public constructor(
    public readonly code: PhoneControlErrorCode,
    message: string,
    public readonly details: ErrorDetails = {}
  ) {
    super(message);
  }
}

export function toMachineError(error: PhoneControlError): MachineError {
  return {
    ok: false,
    error: {
      code: error.code,
      message: error.message,
      details: error.details
    }
  };
}

export function asPhoneControlError(
  error: unknown,
  fallbackCode: PhoneControlErrorCode = "INTERNAL_ERROR"
): PhoneControlError {
  if (error instanceof PhoneControlError) {
    return error;
  }

  const message = error instanceof Error ? error.message : String(error);
  return new PhoneControlError(fallbackCode, message);
}
