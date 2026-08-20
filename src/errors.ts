export const PHONE_CONTROL_ERROR_CODES = [
  "POLICY_NOT_FOUND",
  "POLICY_INVALID",
  "ADB_NOT_FOUND",
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
  "OBSERVATION_FAILED",
  "WAIT_TIMEOUT",
  "INTERNAL_ERROR"
] as const;

export type PhoneControlErrorCode =
  (typeof PHONE_CONTROL_ERROR_CODES)[number];

export type ErrorDetails = Readonly<Record<string, unknown>>;

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
