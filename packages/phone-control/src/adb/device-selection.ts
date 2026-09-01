import { PhoneControlError } from "../errors.js";
import type { DeviceInfo, DeviceState } from "../types.js";

function parseState(tokens: string[]): DeviceState {
  const state = tokens[1];
  if (state === "device" || state === "offline" || state === "unauthorized") {
    return state;
  }
  if (state === "no" && tokens[2] === "permissions") {
    return "no permissions";
  }
  return "unknown";
}

export function parseAdbDevices(output: string): DeviceInfo[] {
  const devices: DeviceInfo[] = [];

  for (const line of output.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("List of devices attached")) {
      continue;
    }

    const tokens = trimmed.split(/\s+/);
    if (tokens.length < 2) {
      continue;
    }

    const state = parseState(tokens);
    devices.push({
      serial: tokens[0],
      state,
      authorized: state === "device"
    });
  }

  return devices;
}

export function selectAuthorizedDevice(
  devices: readonly DeviceInfo[],
  configuredSerial?: string
): DeviceInfo {
  const serial = configuredSerial?.trim();
  if (serial) {
    const configured = devices.find(
      (device) =>
        device.serial === serial ||
        device.serial.includes(serial)
    );
    if (configured && configured.authorized) {
      return configured;
    }
  }

  const authorized = devices.filter((device) => device.authorized);
  if (authorized.length === 1) {
    return authorized[0];
  }

  if (serial) {
    throw new PhoneControlError(
      "DEVICE_NOT_FOUND",
      `Configured device '${serial}' is not an authorized device.`,
      {
        configuredSerial: serial,
        availableDevices: devices.map(({ serial: value, state }) => ({
          serial: value,
          state
        }))
      }
    );
  }

  if (authorized.length === 0) {
    throw new PhoneControlError(
      "NO_AUTHORIZED_DEVICE",
      "Exactly one authorized Android device is required, but none was found.",
      { devices }
    );
  }

  throw new PhoneControlError(
    "MULTIPLE_AUTHORIZED_DEVICES",
    "PHONE_CONTROL_DEVICE_SERIAL is required when more than one authorized Android device is connected.",
    { devices: authorized }
  );
}

export function selectDeviceFromEnvironment(
  devices: readonly DeviceInfo[],
  env: NodeJS.ProcessEnv = process.env
): DeviceInfo {
  return selectAuthorizedDevice(devices, env.PHONE_CONTROL_DEVICE_SERIAL);
}
