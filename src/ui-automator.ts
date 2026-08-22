import { randomBytes } from "node:crypto";

import { PhoneControlError } from "./errors.js";
import type { Bounds, UiElement, UiElementStates } from "./types.js";
import { hashText } from "./adb/process-parsers.js";

type ElementRefFactory = () => string;

const BOOLEAN_ATTRIBUTES: Readonly<Record<string, string>> = {
  checkable: "checkable",
  checked: "checked",
  clickable: "clickable",
  enabled: "enabled",
  focusable: "focusable",
  focused: "focused",
  "long-clickable": "longClickable",
  password: "password",
  scrollable: "scrollable",
  selected: "selected",
  "visible-to-user": "visibleToUser",
  "accessibility-focused": "accessibilityFocused"
};

function createOpaqueElementRef(): string {
  return `el_${randomBytes(12).toString("base64url")}`;
}

function unescapeXml(value: string): string {
  return value
    .replaceAll("&quot;", '"')
    .replaceAll("&apos;", "'")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&amp;", "&");
}

function parseAttributes(fragment: string): Record<string, string> {
  const attributes: Record<string, string> = {};
  const expression = /([A-Za-z_:][A-Za-z0-9_.:-]*)\s*=\s*"([^"]*)"/g;
  for (const match of fragment.matchAll(expression)) {
    attributes[match[1]] = unescapeXml(match[2]);
  }
  return attributes;
}

function parseBoolean(value: string | undefined): boolean | undefined {
  if (value === undefined) {
    return undefined;
  }
  return value.toLowerCase() === "true";
}

export function parseBounds(value: string | undefined): Bounds | null {
  if (!value) {
    return null;
  }
  const match = value.match(/^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$/);
  if (!match) {
    return null;
  }
  const bounds: Bounds = {
    left: Number(match[1]),
    top: Number(match[2]),
    right: Number(match[3]),
    bottom: Number(match[4])
  };
  if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) {
    return null;
  }
  return bounds;
}

export function boundsCenter(bounds: Bounds): { x: number; y: number } {
  if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) {
    throw new PhoneControlError(
      "ELEMENT_NO_BOUNDS",
      "The UI element does not have a usable bounds rectangle."
    );
  }
  return {
    x: Math.floor((bounds.left + bounds.right) / 2),
    y: Math.floor((bounds.top + bounds.bottom) / 2)
  };
}

function parseStates(attributes: Record<string, string>): UiElementStates {
  const states: UiElementStates = {};
  for (const [xmlName, stateName] of Object.entries(BOOLEAN_ATTRIBUTES)) {
    const value = parseBoolean(attributes[xmlName]);
    if (value !== undefined) {
      states[stateName] = value;
    }
  }
  return states;
}

export function parseUiAutomatorXml(
  xml: string,
  refFactory: ElementRefFactory = createOpaqueElementRef
): UiElement[] {
  const elements: UiElement[] = [];
  const parentStack: number[] = [];
  const nodeExpression = /<node\b([^>]*)>|<\/node\s*>/gi;
  for (const match of xml.matchAll(nodeExpression)) {
    if (match[0].startsWith("</")) {
      parentStack.pop();
      continue;
    }
    const attributes = parseAttributes(match[1]);
    const elementIndex = elements.length;
    elements.push({
      elementRef: refFactory(),
      text: attributes.text ?? "",
      contentDescription: attributes["content-desc"] ?? "",
      resourceId: attributes["resource-id"] ?? "",
      class: attributes.class ?? "",
      states: parseStates(attributes),
      bounds: parseBounds(attributes.bounds),
      parentIndex: parentStack[parentStack.length - 1] ?? null
    });
    if (!/\/\s*>$/.test(match[0])) {
      parentStack.push(elementIndex);
    }
  }
  return elements;
}

function canonicalElement(element: UiElement): string {
  const states = Object.fromEntries(
    Object.entries(element.states).sort(([left], [right]) => left.localeCompare(right))
  );
  return JSON.stringify({
    text: element.text,
    contentDescription: element.contentDescription,
    resourceId: element.resourceId,
    class: element.class,
    states,
    bounds: element.bounds,
    parentIndex: element.parentIndex ?? null
  });
}

export function hashUiTree(elements: readonly UiElement[]): string {
  return hashText(elements.map(canonicalElement).join("\n"));
}

/** A stable, server-owned structural identity for a node. Volatile semantic
 * values, bounds, and transient states are deliberately excluded. */
export function elementStructureKey(element: UiElement): string {
  return JSON.stringify({
    resourceId: element.resourceId,
    class: element.class
  });
}

/** States that can change the meaning or outcome of acting on the target. */
export function elementActionStateKey(element: UiElement): string {
  return JSON.stringify({
    checkable: element.states.checkable,
    checked: element.states.checked,
    clickable: element.states.clickable,
    enabled: element.states.enabled,
    longClickable: element.states.longClickable,
    password: element.states.password,
    scrollable: element.states.scrollable,
    selected: element.states.selected,
    visibleToUser: element.states.visibleToUser
  });
}

export function findUniqueElementMatch(
  target: UiElement,
  observed: readonly UiElement[],
  current: readonly UiElement[]
): UiElement | undefined {
  const targetAncestorPath = elementAncestorStructurePath(target, observed);
  const exact = current.filter(
    (candidate) =>
      elementStructureKey(candidate) === elementStructureKey(target) &&
      candidate.text === target.text &&
      candidate.contentDescription === target.contentDescription &&
      candidate.bounds !== null &&
      elementAncestorStructurePath(candidate, current) === targetAncestorPath
  );
  if (exact.length === 1) return exact[0];
  return undefined;
}

function elementAncestorStructurePath(
  element: UiElement,
  elements: readonly UiElement[]
): string {
  const path: string[] = [];
  const visited = new Set<number>();
  let parentIndex = element.parentIndex;
  while (
    parentIndex !== undefined &&
    parentIndex !== null &&
    parentIndex >= 0 &&
    parentIndex < elements.length &&
    !visited.has(parentIndex)
  ) {
    visited.add(parentIndex);
    const parent = elements[parentIndex];
    path.unshift(elementStructureKey(parent));
    parentIndex = parent.parentIndex;
  }
  return JSON.stringify(path);
}

function isDescendantOf(
  candidateIndex: number,
  ancestorIndex: number,
  elements: readonly UiElement[]
): boolean {
  const visited = new Set<number>();
  let parentIndex = elements[candidateIndex]?.parentIndex;
  while (
    parentIndex !== undefined &&
    parentIndex !== null &&
    parentIndex >= 0 &&
    parentIndex < elements.length &&
    !visited.has(parentIndex)
  ) {
    if (parentIndex === ancestorIndex) return true;
    visited.add(parentIndex);
    parentIndex = elements[parentIndex].parentIndex;
  }
  return false;
}

/**
 * UI Automator emits hierarchy traversal order. A newly covering, later
 * non-descendant is conservatively treated as an overlay; a future Android-side
 * bridge should replace this heuristic with real window and z-order metadata.
 */
export function isElementPotentiallyObscured(
  target: UiElement,
  current: readonly UiElement[],
  observedTarget: UiElement,
  observed: readonly UiElement[]
): boolean {
  const previousCoveringNodes = coveringLaterNodeCounts(observedTarget, observed);
  for (const key of coveringLaterNodeKeys(target, current)) {
    const previousCount = previousCoveringNodes.get(key) ?? 0;
    if (previousCount === 0) return true;
    previousCoveringNodes.set(key, previousCount - 1);
  }
  return false;
}

function coveringLaterNodeCounts(
  target: UiElement,
  elements: readonly UiElement[]
): Map<string, number> {
  const counts = new Map<string, number>();
  for (const key of coveringLaterNodeKeys(target, elements)) {
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return counts;
}

function coveringLaterNodeKeys(
  target: UiElement,
  elements: readonly UiElement[]
): string[] {
  if (!target.bounds) return [];
  const targetIndex = elements.indexOf(target);
  if (targetIndex < 0) return [];
  const center = boundsCenter(target.bounds);
  const keys: string[] = [];
  for (let index = targetIndex + 1; index < elements.length; index += 1) {
    const candidate = elements[index];
    if (
      !candidate.bounds ||
      candidate.states.visibleToUser === false ||
      isDescendantOf(index, targetIndex, elements)
    ) {
      continue;
    }
    if (
      center.x >= candidate.bounds.left &&
      center.x < candidate.bounds.right &&
      center.y >= candidate.bounds.top &&
      center.y < candidate.bounds.bottom
    ) {
      keys.push(
        `${elementStructureKey(candidate)}:${elementAncestorStructurePath(
          candidate,
          elements
        )}`
      );
    }
  }
  return keys;
}
