import type { CompanionJsonValue } from "../companion-events.js";

export type { CompanionJsonValue } from "../companion-events.js";

export type CompanionProcessStatus = "stopped" | "starting" | "running" | "stopping" | "error";
export type BridgeStatus = "unknown" | "checking" | "connected" | "offline";

export interface CompanionSettingsSnapshot {
  host: string;
  port: number;
  tokenConfigured: boolean;
  pairingConfigured: boolean;
}

export interface CompanionSettingsInput {
  host: string;
  port: number;
  /** An empty value keeps the token already saved in the companion. */
  token?: string;
}

export interface PairingInput {
  code: string;
}

export interface PhoneSnapshot {
  state: string;
  active: boolean;
  sessionId?: string;
  request?: string;
  currentPurpose?: string;
  requestAvailable?: boolean;
}

export interface CompanionLogEntry {
  id: string;
  timestamp: number;
  level: "info" | "error" | "system";
  source: "companion" | "bridge" | "system";
  message: string;
}

export type CompanionToolCallStatus = "running" | "success" | "error";

export interface CompanionToolCallImageContent {
  type: "image";
  imageUrl: string;
  mimeType: string;
  index: number;
}

export interface CompanionToolCallDebugImage extends CompanionToolCallImageContent {
  label: "before" | "after";
}

export interface CompanionToolCallResponse {
  isError?: boolean;
  images: CompanionToolCallImageContent[];
  debugImages?: CompanionToolCallDebugImage[];
  structuredContent?: { [key: string]: CompanionJsonValue };
}

export interface CompanionToolCall {
  id: string;
  tool: string;
  arguments: CompanionJsonValue;
  rawArguments?: string;
  startedAt: number;
  completedAt?: number;
  durationMs?: number;
  status: CompanionToolCallStatus;
  response?: CompanionToolCallResponse;
  error?: string;
}

export interface CompanionState {
  processStatus: CompanionProcessStatus;
  bridgeStatus: BridgeStatus;
  settings: CompanionSettingsSnapshot;
  phone?: PhoneSnapshot;
  lastError?: string;
  logs: CompanionLogEntry[];
  toolCalls: CompanionToolCall[];
}

export interface BridgeCheckResult {
  ok: boolean;
  message: string;
  phone?: PhoneSnapshot;
}

export interface CompanionClientApi {
  getState(): Promise<CompanionState>;
  saveSettings(input: CompanionSettingsInput): Promise<CompanionState>;
  pairWithPhone(input: PairingInput): Promise<CompanionState>;
  checkConnection(): Promise<BridgeCheckResult>;
  startCompanion(): Promise<CompanionState>;
  stopCompanion(): Promise<CompanionState>;
  clearLogs(): Promise<CompanionState>;
  clearToolCalls(): Promise<CompanionState>;
  onState(callback: (state: CompanionState) => void): () => void;
}
