export interface GeometryRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface GeometryRecoveryState {
  lastSample?: GeometryRect;
  stableSamples: number;
  attached: boolean;
}

export interface GeometryRecoveryResult {
  state: GeometryRecoveryState;
  attached: boolean;
  changed: boolean;
}

function sameRect(left: GeometryRect | undefined, right: GeometryRect): boolean {
  return (
    left?.x === right.x &&
    left.y === right.y &&
    left.width === right.width &&
    left.height === right.height
  );
}

export function createGeometryRecoveryState(): GeometryRecoveryState {
  return { stableSamples: 0, attached: false };
}

export function updateGeometryRecovery(
  state: GeometryRecoveryState,
  sample: GeometryRect | undefined,
  requiredStableSamples = 2
): GeometryRecoveryResult {
  if (!sample) {
    const nextState = createGeometryRecoveryState();
    return { state: nextState, attached: false, changed: state.attached };
  }

  const changed = !sameRect(state.lastSample, sample);
  const stableSamples = changed ? 1 : state.stableSamples + 1;
  const attached = stableSamples >= Math.max(1, requiredStableSamples);
  const nextState: GeometryRecoveryState = {
    lastSample: { ...sample },
    stableSamples,
    attached
  };
  return { state: nextState, attached, changed };
}
