export const PAIRING_CODE_LENGTH = 8;
export const PAIRING_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

const PAIRING_CODE_PATTERN = new RegExp(`^[${PAIRING_CODE_ALPHABET}]{${PAIRING_CODE_LENGTH}}$`);

// Standard dash variants, minus signs, and Unicode whitespace
const DASH_AND_WHITESPACE_REGEX = /[\s\-\u2010-\u2015\u2212\uFE63\uFF0D]/g;

/** Normalize a code typed from the phone. */
export function normalizePairingCode(value: string): string {
  const normalized = value.replace(DASH_AND_WHITESPACE_REGEX, "").trim().toUpperCase();
  if (!PAIRING_CODE_PATTERN.test(normalized)) {
    throw new Error("The DHD pairing code must be 8 letters or numbers.");
  }
  return normalized;
}

export function displayPairingCode(value: string): string {
  const normalized = normalizePairingCode(value);
  return `${normalized.slice(0, 4)}-${normalized.slice(4)}`;
}

/** Format draft input in real-time as the user types in the input field. */
export function formatPairingCodeDraft(value: string): string {
  const clean = value.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, PAIRING_CODE_LENGTH);
  if (clean.length >= 4) {
    return `${clean.slice(0, 4)}-${clean.slice(4)}`;
  }
  return clean;
}
