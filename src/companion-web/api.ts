export type CompanionProcessStatus = "stopped" | "starting" | "running" | "stopping" | "error";
export type BridgeStatus = "unknown" | "checking" | "connected" | "offline";

export interface CompanionSettingsSnapshot {
  host: string;
  port: number;
  tokenConfigured: boolean;
}

export interface CompanionSettingsInput {
  host: string;
  port: number;
  /** An empty value keeps the token already saved in the companion. */
  token?: string;
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

export interface CompanionState {
  processStatus: CompanionProcessStatus;
  bridgeStatus: BridgeStatus;
  settings: CompanionSettingsSnapshot;
  phone?: PhoneSnapshot;
  lastError?: string;
  logs: CompanionLogEntry[];
}

export interface BridgeCheckResult {
  ok: boolean;
  message: string;
  phone?: PhoneSnapshot;
}

export interface CompanionClientApi {
  getState(): Promise<CompanionState>;
  saveSettings(input: CompanionSettingsInput): Promise<CompanionState>;
  checkConnection(): Promise<BridgeCheckResult>;
  startCompanion(): Promise<CompanionState>;
  stopCompanion(): Promise<CompanionState>;
  clearLogs(): Promise<CompanionState>;
  onState(callback: (state: CompanionState) => void): () => void;
}
