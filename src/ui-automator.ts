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
  const nodeExpression = /<node\b([^>]*?)(?:\/?>)/gi;
  for (const match of xml.matchAll(nodeExpression)) {
    const attributes = parseAttributes(match[1]);
    elements.push({
      elementRef: refFactory(),
      text: attributes.text ?? "",
      contentDescription: attributes["content-desc"] ?? "",
      resourceId: attributes["resource-id"] ?? "",
      class: attributes.class ?? "",
      states: parseStates(attributes),
      bounds: parseBounds(attributes.bounds)
    });
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
    bounds: element.bounds
  });
}

export function hashUiTree(elements: readonly UiElement[]): string {
  return hashText(elements.map(canonicalElement).join("\n"));
}
