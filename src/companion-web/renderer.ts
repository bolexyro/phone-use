import type {
  CompanionClientApi,
  CompanionLogEntry,
  CompanionState
} from "./api.js";

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
  toggleTokenVisibility: byId<HTMLButtonElement>("toggle-token-visibility"),
  save: byId<HTMLButtonElement>("save-settings"),
  check: byId<HTMLButtonElement>("check-connection"),
  start: byId<HTMLButtonElement>("start-companion"),
  stop: byId<HTMLButtonElement>("stop-companion"),
  logList: byId<HTMLDivElement>("log-list"),
  logScrollContainer: byId<HTMLDivElement>("log-scroll-container"),
  toast: byId<HTMLDivElement>("toast")
};

let toastTimer: number | undefined;

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

  setText(elements.tokenState, next.settings.tokenConfigured ? "CONFIGURED" : "NOT_CONFIGURED");

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
  elements.token.placeholder = next.settings.tokenConfigured ? "Token configured (enter new token to replace)" : "Paste pairing token from DHD Settings";
  renderLogs(next.logs);
}

function showToast(message: string, isError = false): void {
  elements.toast.textContent = message;
  elements.toast.className = `toast-box font-mono visible${isError ? " error" : ""}`;
  if (toastTimer !== undefined) window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => {
    elements.toast.className = "toast-box font-mono";
  }, 3_500);
}

async function refreshState(): Promise<void> {
  render(await api.getState());
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
      showToast(err instanceof Error ? err.message : String(err), true);
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

// Actions
elements.save.addEventListener("click", async () => {
  try {
    const token = elements.token.value.trim();
    render(await api.saveSettings({
      host: elements.host.value,
      port: Number(elements.port.value),
      ...(token ? { token } : {})
    }));
    elements.token.value = "";
    showToast("Connection settings saved.");
  } catch (error) {
    showToast(error instanceof Error ? error.message : String(error), true);
  }
});

elements.check.addEventListener("click", async () => {
  try {
    const result = await api.checkConnection();
    await refreshState();
    showToast(result.message, !result.ok);
  } catch (error) {
    showToast(error instanceof Error ? error.message : String(error), true);
  }
});

elements.start.addEventListener("click", async () => {
  try {
    render(await api.startCompanion());
    showToast("Companion worker started.");
  } catch (error) {
    showToast(error instanceof Error ? error.message : String(error), true);
  }
});

elements.stop.addEventListener("click", async () => {
  try {
    render(await api.stopCompanion());
    showToast("Companion worker stopped.");
  } catch (error) {
    showToast(error instanceof Error ? error.message : String(error), true);
  }
});

api.onState(render);
void refreshState().catch((error: unknown) => {
  showToast(error instanceof Error ? error.message : String(error), true);
});
