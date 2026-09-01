import type {
  CompanionClientApi,
  CompanionLogEntry,
  CompanionState
} from "./api.js";
import {
  displayPairingCode,
  formatPairingCodeDraft,
  normalizePairingCode
} from "./pairing-code.js";

function createWebApi(): CompanionClientApi {
  return {
    async getState(): Promise<CompanionState> {
      const res = await fetch("/api/state");
      if (!res.ok) throw new Error(`Server returned ${res.status}: ${res.statusText}`);
      return res.json();
    },
    async saveSettings(input): Promise<CompanionState> {
      const res = await fetch("/api/settings", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(input)
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      return res.json();
    },
    async pairWithPhone(input): Promise<CompanionState> {
      const res = await fetch("/api/pair", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(input)
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      return res.json();
    },
    async checkConnection() {
      const res = await fetch("/api/check", { method: "POST" });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      return res.json();
    },
    async startCompanion(): Promise<CompanionState> {
      const res = await fetch("/api/start", { method: "POST" });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      return res.json();
    },
    async stopCompanion(): Promise<CompanionState> {
      const res = await fetch("/api/stop", { method: "POST" });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      return res.json();
    },
    async clearLogs(): Promise<CompanionState> {
      const res = await fetch("/api/clear-logs", { method: "POST" });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.message || `Server returned ${res.status}`);
      }
      return res.json();
    },
    onState(callback: (state: CompanionState) => void) {
      const source = new EventSource("/api/events");
      source.onmessage = (event) => {
        try {
          const state = JSON.parse(event.data) as CompanionState;
          callback(state);
        } catch (err) {
          console.error("Failed to parse SSE state:", err);
        }
      };

      // Hot reload listener for live development
      source.addEventListener("reload", (event: MessageEvent) => {
        try {
          const data = JSON.parse(event.data) as { type?: string; file?: string };
          if (data.type === "css") {
            const links = document.querySelectorAll<HTMLLinkElement>('link[rel="stylesheet"]');
            links.forEach((link) => {
              const url = new URL(link.href, window.location.origin);
              url.searchParams.set("_reload", String(Date.now()));
              link.href = url.toString();
            });
          } else {
            window.location.reload();
          }
        } catch {
          window.location.reload();
        }
      });

      return () => source.close();
    }
  };
}

const api: CompanionClientApi = createWebApi();
const byId = <T extends HTMLElement>(id: string): T => document.getElementById(id) as T;

const elements = {
  appStatusDetail: byId<HTMLSpanElement>("app-status-detail"),
  headerTarget: document.getElementById("header-target") as HTMLSpanElement | null,
  bridgeState: byId<HTMLSpanElement>("bridge-state"),
  bridgeTarget: byId<HTMLSpanElement>("bridge-target"),
  phoneState: byId<HTMLSpanElement>("phone-state"),
  phonePurpose: byId<HTMLHeadingElement>("phone-purpose"),
  phoneRequest: byId<HTMLPreElement>("phone-request"),
  processState: byId<HTMLSpanElement>("process-state"),
  tokenState: byId<HTMLSpanElement>("token-state"),
  lastError: byId<HTMLDivElement>("last-error"),
  sessionBadge: byId<HTMLSpanElement>("session-badge"),
  logCount: byId<HTMLSpanElement>("log-count"),
  logCountBadge: byId<HTMLSpanElement>("log-count-badge"),
  clearLogs: byId<HTMLButtonElement>("clear-logs"),
  host: byId<HTMLInputElement>("host-input"),
  port: byId<HTMLInputElement>("port-input"),
  token: byId<HTMLInputElement>("token-input"),
  pairingCode: byId<HTMLInputElement>("pairing-code-input"),
  pair: byId<HTMLButtonElement>("pair-phone"),
  toggleTokenVisibility: byId<HTMLButtonElement>("toggle-token-visibility"),
  save: byId<HTMLButtonElement>("save-settings"),
  check: byId<HTMLButtonElement>("check-connection"),
  start: byId<HTMLButtonElement>("start-companion"),
  stop: byId<HTMLButtonElement>("stop-companion"),
  logList: byId<HTMLDivElement>("log-list"),
  logScrollContainer: byId<HTMLDivElement>("log-scroll-container"),
  toast: byId<HTMLDivElement>("toast"),
  toastIcon: document.getElementById("toast-icon") as HTMLDivElement | null,
  toastMessage: document.getElementById("toast-message") as HTMLSpanElement | null,
  toastClose: document.getElementById("toast-close") as HTMLButtonElement | null,
  connectionStatusPill: document.getElementById("connection-status-pill") as HTMLSpanElement | null,
  pairingStatusHint: document.getElementById("pairing-status-hint") as HTMLSpanElement | null
};

let toastTimer: number | undefined;

const TOAST_ICONS = {
  success: `<svg class="toast-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><path stroke-linecap="round" stroke-linejoin="round" d="M20 6L9 17l-5-5"/></svg>`,
  error: `<svg class="toast-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>`,
  info: `<svg class="toast-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>`
};

const SPINNER_SVG = `<svg class="btn-svg spinner" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><circle cx="12" cy="12" r="10" stroke-opacity="0.25" stroke="currentColor" fill="none"/><path d="M12 2a10 10 0 0 1 10 10" stroke="currentColor" stroke-linecap="round"/></svg>`;

async function withButtonLoading<T>(
  button: HTMLButtonElement,
  loadingText: string,
  action: () => Promise<T>
): Promise<T> {
  const originalHtml = button.innerHTML;
  const originalDisabled = button.disabled;
  button.disabled = true;
  button.innerHTML = `${SPINNER_SVG}<span>${loadingText}</span>`;
  try {
    return await action();
  } finally {
    button.innerHTML = originalHtml;
    button.disabled = originalDisabled;
  }
}

function formatTime(timestamp: number): string {
  return new Intl.DateTimeFormat(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
  }).format(timestamp);
}

function setText(element: HTMLElement, value: string): void {
  element.textContent = value;
}

function renderLogLine(entry: CompanionLogEntry): HTMLElement {
  const row = document.createElement("div");
  row.className = `log-line ${entry.level}`;

  const time = document.createElement("span");
  time.className = "log-time";
  time.textContent = formatTime(entry.timestamp);

  const source = document.createElement("span");
  source.className = `log-source ${entry.source}`;
  source.textContent = entry.source;

  const message = document.createElement("span");
  message.className = "log-msg";
  message.textContent = entry.message;

  row.append(time, source, message);
  return row;
}

function renderLogs(entries: CompanionLogEntry[]): void {
  elements.logList.replaceChildren();
  const countStr = `${entries.length}`;
  elements.logCount.textContent = `${entries.length} events`;
  elements.logCountBadge.textContent = countStr;

  if (entries.length === 0) {
    const empty = document.createElement("div");
    empty.className = "empty-console font-mono";
    empty.textContent = "No events recorded. Start the companion worker or test the phone link.";
    elements.logList.append(empty);
    return;
  }

  for (const entry of entries) {
    elements.logList.append(renderLogLine(entry));
  }

  if (elements.logScrollContainer) {
    elements.logScrollContainer.scrollTop = elements.logScrollContainer.scrollHeight;
  }
}

function render(next: CompanionState): void {
  const targetStr = `${next.settings.host}:${next.settings.port}`;
  if (elements.headerTarget) elements.headerTarget.textContent = targetStr;

  const isWorkerRunning = next.processStatus === "running";
  const isWorkerStarting = next.processStatus === "starting";
  const isWorkerBusy = isWorkerRunning || isWorkerStarting;

  elements.appStatusDetail.textContent = isWorkerRunning
    ? "worker active // listening"
    : "ready";

  // Toggle Start / Stop action buttons
  if (isWorkerBusy) {
    elements.start.style.display = "none";
    elements.stop.style.display = "inline-flex";
    elements.stop.disabled = next.processStatus === "stopping";
  } else {
    elements.start.style.display = "inline-flex";
    elements.stop.style.display = "none";
    elements.start.disabled = false;
  }

  setText(elements.bridgeState, next.bridgeStatus.toUpperCase());
  elements.bridgeState.className = `state-badge font-mono ${next.bridgeStatus}`;

  setText(elements.bridgeTarget, targetStr);
  setText(elements.processState, next.processStatus.toUpperCase());
  elements.processState.className = `state-badge font-mono ${next.processStatus}`;

  const isPaired = next.settings.pairingConfigured;
  const isManual = next.settings.tokenConfigured;

  setText(elements.tokenState, isPaired ? "PAIRED" : isManual ? "MANUAL" : "NOT_CONFIGURED");

  if (elements.connectionStatusPill) {
    if (isPaired) {
      elements.connectionStatusPill.textContent = "PAIRED";
      elements.connectionStatusPill.className = "state-badge font-mono connected";
    } else if (isManual) {
      elements.connectionStatusPill.textContent = "MANUAL TOKEN";
      elements.connectionStatusPill.className = "state-badge font-mono connected";
    } else {
      elements.connectionStatusPill.textContent = "NOT PAIRED";
      elements.connectionStatusPill.className = "state-badge font-mono stopped";
    }
  }

  if (elements.pairingStatusHint) {
    elements.pairingStatusHint.textContent = isPaired ? "✓ Saved (enter code to re-pair)" : "";
  }

  const phoneState = next.phone;
  const isPhoneActive = phoneState?.active === true;
  setText(elements.phoneState, (phoneState?.state ?? "NOT_CHECKED").toUpperCase());
  setText(elements.phonePurpose, phoneState?.currentPurpose || (isPhoneActive ? "Active Phone Session" : "Waiting for phone session..."));
  setText(elements.phoneRequest, phoneState?.request || "No active request reported by the phone.");

  elements.sessionBadge.textContent = isPhoneActive ? (phoneState?.state ?? "ACTIVE").toUpperCase() : "IDLE";
  elements.sessionBadge.className = `state-badge font-mono ${isPhoneActive ? "active" : ""}`;

  elements.lastError.textContent = next.lastError || "";
  elements.lastError.hidden = !next.lastError;
  elements.check.disabled = next.bridgeStatus === "checking";

  if (document.activeElement !== elements.host) elements.host.value = next.settings.host;
  if (document.activeElement !== elements.port) elements.port.value = String(next.settings.port);
  elements.token.placeholder = isManual ? "Token configured (enter new token to replace)" : "Paste manual bridge token";
  elements.pairingCode.placeholder = "ABCD-2345";
  renderLogs(next.logs);
}

function hideToast(): void {
  elements.toast.classList.remove("visible");
  if (toastTimer !== undefined) {
    window.clearTimeout(toastTimer);
    toastTimer = undefined;
  }
}

function showToast(message: string, variant: "success" | "error" | "info" = "info"): void {
  if (elements.toastMessage) {
    elements.toastMessage.textContent = message;
  } else {
    elements.toast.textContent = message;
  }

  if (elements.toastIcon) {
    elements.toastIcon.innerHTML = TOAST_ICONS[variant];
  }

  elements.toast.className = `toast-box visible ${variant}`;

  if (toastTimer !== undefined) window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(hideToast, 4_000);
}

if (elements.toastClose) {
  elements.toastClose.addEventListener("click", hideToast);
}

async function refreshState(): Promise<void> {
  const state = await api.getState();
  render(state);
  // If state is unknown and token/pairing is configured, verify bridge link
  if (state.bridgeStatus === "unknown" && (state.settings.tokenConfigured || state.settings.pairingConfigured)) {
    void api.checkConnection().then(() => api.getState().then(render)).catch(() => {});
  }
}

// Tab Switching
document.querySelectorAll<HTMLButtonElement>(".tab-btn").forEach((btn) => {
  btn.addEventListener("click", () => {
    const tabName = btn.getAttribute("data-tab");
    if (!tabName) return;

    document.querySelectorAll(".tab-btn").forEach((b) => b.classList.remove("active"));
    document.querySelectorAll(".tab-panel").forEach((p) => p.classList.remove("active"));

    btn.classList.add("active");
    const panel = document.getElementById(`tab-${tabName}`);
    if (panel) panel.classList.add("active");
  });
});

// Password visibility toggle
if (elements.toggleTokenVisibility) {
  elements.toggleTokenVisibility.addEventListener("click", () => {
    const isPassword = elements.token.type === "password";
    elements.token.type = isPassword ? "text" : "password";
  });
}

// Clear Logs
if (elements.clearLogs) {
  elements.clearLogs.addEventListener("click", async () => {
    try {
      const nextState = await api.clearLogs();
      render(nextState);
    } catch (err) {
      showToast(err instanceof Error ? err.message : String(err), "error");
    }
  });
}

// Theme Management
const themeToggle = document.getElementById("theme-toggle") as HTMLButtonElement | null;
const sunIcon = document.querySelector(".theme-icon-sun") as SVGElement | null;
const moonIcon = document.querySelector(".theme-icon-moon") as SVGElement | null;

function applyTheme(theme: "dark" | "light") {
  document.documentElement.setAttribute("data-theme", theme);
  localStorage.setItem("dhd_companion_theme", theme);
  if (sunIcon && moonIcon) {
    if (theme === "light") {
      sunIcon.style.display = "none";
      moonIcon.style.display = "block";
    } else {
      sunIcon.style.display = "block";
      moonIcon.style.display = "none";
    }
  }
}

const savedTheme = (localStorage.getItem("dhd_companion_theme") as "dark" | "light" | null) || "dark";
applyTheme(savedTheme);

if (themeToggle) {
  themeToggle.addEventListener("click", () => {
    const currentTheme = document.documentElement.getAttribute("data-theme") === "light" ? "light" : "dark";
    const nextTheme = currentTheme === "dark" ? "light" : "dark";
    applyTheme(nextTheme);
  });
}

// Pairing Code Auto-Hyphenation & Formatting
elements.pairingCode.addEventListener("keydown", (event) => {
  if (event.key === "Backspace") {
    const input = elements.pairingCode;
    const start = input.selectionStart ?? 0;
    const end = input.selectionEnd ?? 0;
    // When deleting right after the auto-inserted hyphen (e.g. "ABCD-"), remove both hyphen and preceding char
    if (start === end && start === 5 && input.value.charAt(4) === "-") {
      event.preventDefault();
      input.value = input.value.slice(0, 3);
      input.setSelectionRange(3, 3);
      return;
    }
  }

  if (event.key === "Enter") {
    event.preventDefault();
    elements.pair.click();
  }
});

elements.pairingCode.addEventListener("input", () => {
  const input = elements.pairingCode;
  const currentVal = input.value;
  const formatted = formatPairingCodeDraft(currentVal);
  if (formatted !== currentVal) {
    input.value = formatted;
  }
});

// Actions
elements.save.addEventListener("click", async () => {
  try {
    await withButtonLoading(elements.save, "Saving...", async () => {
      const token = elements.token.value.trim();
      render(await api.saveSettings({
        host: elements.host.value,
        port: Number(elements.port.value),
        ...(token ? { token } : {})
      }));
      elements.token.value = "";
    });
    showToast("Connection settings saved.", "success");
  } catch (error) {
    showToast(error instanceof Error ? error.message : String(error), "error");
  }
});

async function pairWithPhone(value: string): Promise<void> {
  const code = normalizePairingCode(value);
  elements.pairingCode.disabled = true;
  try {
    await withButtonLoading(elements.pair, "Pairing...", async () => {
      render(await api.pairWithPhone({ code }));
      elements.pairingCode.value = "";
    });
    showToast(`Paired with DHD using ${displayPairingCode(code)}.`, "success");
  } finally {
    elements.pairingCode.disabled = false;
  }
}

elements.pair.addEventListener("click", async () => {
  try {
    await pairWithPhone(elements.pairingCode.value);
  } catch (error) {
    showToast(error instanceof Error ? error.message : String(error), "error");
  }
});

elements.check.addEventListener("click", async () => {
  try {
    let checkResult: { ok: boolean; message: string } | undefined;
    await withButtonLoading(elements.check, "Checking...", async () => {
      checkResult = await api.checkConnection();
      await refreshState();
    });
    if (checkResult) {
      showToast(checkResult.message, checkResult.ok ? "success" : "error");
    }
  } catch (error) {
    showToast(error instanceof Error ? error.message : String(error), "error");
  }
});

elements.start.addEventListener("click", async () => {
  try {
    await withButtonLoading(elements.start, "Starting...", async () => {
      render(await api.startCompanion());
    });
    showToast("Companion worker started.", "success");
  } catch (error) {
    showToast(error instanceof Error ? error.message : String(error), "error");
  }
});

elements.stop.addEventListener("click", async () => {
  try {
    await withButtonLoading(elements.stop, "Stopping...", async () => {
      render(await api.stopCompanion());
    });
    showToast("Companion worker stopped.", "info");
  } catch (error) {
    showToast(error instanceof Error ? error.message : String(error), "error");
  }
});

api.onState(render);
void refreshState().catch((error: unknown) => {
  showToast(error instanceof Error ? error.message : String(error), "error");
});
