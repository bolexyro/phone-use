import { PhoneControlError } from "./errors.js";
import type { ForegroundState, PolicyProfile } from "./types.js";

export function isAllowedPackage(
  policy: PolicyProfile,
  packageName: string | null | undefined
): packageName is string {
  return Boolean(packageName && policy.allowedApps.includes(packageName));
}

export function assertAllowedTarget(
  policy: PolicyProfile,
  packageName: string
): void {
  if (!isAllowedPackage(policy, packageName)) {
    throw new PhoneControlError(
      "FORBIDDEN_APP",
      `Package '${packageName}' is not allowed by the active policy.`,
      { packageName, allowedApps: policy.allowedApps }
    );
  }
}

export function assertAllowedForeground(
  policy: PolicyProfile,
  foreground: ForegroundState
): void {
  if (!foreground.packageName) {
    throw new PhoneControlError(
      "FOREGROUND_UNAVAILABLE",
      "The foreground Android package could not be determined."
    );
  }
  assertAllowedTarget(policy, foreground.packageName);
}
