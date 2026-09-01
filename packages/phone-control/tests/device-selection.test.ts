import { describe, expect, it } from "vitest";

import {
  parseAdbDevices,
  selectAuthorizedDevice,
  selectDeviceFromEnvironment
} from "../src/adb/device-selection.js";
import { PhoneControlError } from "../src/errors.js";

describe("authorized device selection", () => {
  it("parses ADB device states and selects the only authorized device", () => {
    const devices = parseAdbDevices(
      "List of devices attached\nphone-1\tdevice product:foo model:Phone transport_id:1\nphone-2\toffline\n"
    );

    expect(devices).toEqual([
      { serial: "phone-1", state: "device", authorized: true },
      { serial: "phone-2", state: "offline", authorized: false }
    ]);
    expect(selectAuthorizedDevice(devices).serial).toBe("phone-1");
  });

  it("requires the configured serial when it is supplied", () => {
    const devices = parseAdbDevices(
      "List of devices attached\nphone-1\tdevice\nphone-2\tdevice\n"
    );

    expect(selectDeviceFromEnvironment(devices, { PHONE_CONTROL_DEVICE_SERIAL: "phone-2" }).serial).toBe(
      "phone-2"
    );
  });

  it("rejects zero, multiple, and unauthorized configured devices with stable codes", () => {
    const devices = parseAdbDevices(
      "List of devices attached\nphone-1\tdevice\nphone-2\tdevice\n"
    );

    expect(() => selectAuthorizedDevice([])).toThrowError(PhoneControlError);
    try {
      selectAuthorizedDevice([]);
    } catch (error) {
      expect((error as PhoneControlError).code).toBe("NO_AUTHORIZED_DEVICE");
    }

    try {
      selectAuthorizedDevice(devices);
    } catch (error) {
      expect((error as PhoneControlError).code).toBe("MULTIPLE_AUTHORIZED_DEVICES");
    }

    try {
      selectAuthorizedDevice(devices, "missing");
    } catch (error) {
      expect((error as PhoneControlError).code).toBe("DEVICE_NOT_FOUND");
    }
  });
});
