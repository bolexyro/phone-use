import type { PointerEvent } from "@dhd/phone-control";

export interface ViewerGeometry {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface MappedCursorPosition {
  localX: number;
  localY: number;
  screenX: number;
  screenY: number;
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.min(maximum, Math.max(minimum, value));
}

export function mapDevicePointToOverlay(
  pointer: { x: number; y: number; displayWidth: number; displayHeight: number },
  geometry: ViewerGeometry
): MappedCursorPosition {
  if (
    !Number.isFinite(pointer.x) ||
    !Number.isFinite(pointer.y) ||
    !Number.isInteger(pointer.displayWidth) ||
    !Number.isInteger(pointer.displayHeight) ||
    pointer.displayWidth <= 0 ||
    pointer.displayHeight <= 0 ||
    geometry.width <= 0 ||
    geometry.height <= 0
  ) {
    throw new RangeError("Pointer and viewer dimensions must be positive finite values.");
  }

  const deviceX = clamp(pointer.x, 0, pointer.displayWidth - 1);
  const deviceY = clamp(pointer.y, 0, pointer.displayHeight - 1);
  const localX = clamp(
    Math.round((deviceX / pointer.displayWidth) * geometry.width),
    0,
    geometry.width - 1
  );
  const localY = clamp(
    Math.round((deviceY / pointer.displayHeight) * geometry.height),
    0,
    geometry.height - 1
  );

  return {
    localX,
    localY,
    screenX: geometry.x + localX,
    screenY: geometry.y + localY
  };
}
