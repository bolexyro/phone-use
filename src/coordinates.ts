import { PhoneControlError } from "./errors.js";
import type {
  DisplayInfo,
  ScrollAmount,
  ScrollDirection
} from "./types.js";
import type { SwipeGesture } from "./adb/adapter.js";

export function assertCoordinateInBounds(
  x: number,
  y: number,
  dimensions: DisplayInfo
): void {
  if (
    !Number.isInteger(x) ||
    !Number.isInteger(y) ||
    x < 0 ||
    y < 0 ||
    x >= dimensions.width ||
    y >= dimensions.height
  ) {
    throw new PhoneControlError(
      "INVALID_COORDINATE",
      `Coordinate (${x}, ${y}) is outside the ${dimensions.width}x${dimensions.height} display.`,
      { x, y, display: dimensions }
    );
  }
}

const SCROLL_DISTANCE_RATIO: Record<ScrollAmount, number> = {
  small: 0.25,
  medium: 0.45,
  large: 0.65
};

const SCROLL_DURATION_MS: Record<ScrollAmount, number> = {
  small: 200,
  medium: 280,
  large: 350
};

export function calculateScrollGesture(
  display: DisplayInfo,
  direction: ScrollDirection,
  amount: ScrollAmount
): SwipeGesture {
  const isVertical = direction === "up" || direction === "down";
  const dimension = isVertical ? display.height : display.width;
  const distance = Math.max(
    1,
    Math.floor(dimension * SCROLL_DISTANCE_RATIO[amount])
  );
  const centerX = Math.floor(display.width / 2);
  const centerY = Math.floor(display.height / 2);
  let x1 = centerX;
  let y1 = centerY;
  let x2 = centerX;
  let y2 = centerY;

  if (direction === "down") {
    // Scrolling down moves viewport down by dragging finger upwards (y1 > y2)
    y1 = Math.min(display.height - 50, centerY + Math.floor(distance / 2));
    y2 = Math.max(50, centerY - Math.ceil(distance / 2));
  } else if (direction === "up") {
    // Scrolling up moves viewport up by dragging finger downwards (y1 < y2)
    y1 = Math.max(50, centerY - Math.floor(distance / 2));
    y2 = Math.min(display.height - 50, centerY + Math.ceil(distance / 2));
  } else if (direction === "right") {
    // Scrolling right moves viewport right by dragging finger leftwards (x1 > x2)
    x1 = Math.min(display.width - 50, centerX + Math.floor(distance / 2));
    x2 = Math.max(50, centerX - Math.ceil(distance / 2));
  } else {
    // Scrolling left moves viewport left by dragging finger rightwards (x1 < x2)
    x1 = Math.max(50, centerX - Math.floor(distance / 2));
    x2 = Math.min(display.width - 50, centerX + Math.ceil(distance / 2));
  }

  assertCoordinateInBounds(x1, y1, display);
  assertCoordinateInBounds(x2, y2, display);
  return { x1, y1, x2, y2, durationMs: SCROLL_DURATION_MS[amount] };
}
