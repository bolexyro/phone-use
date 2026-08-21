import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { join } from "node:path";

export interface ClientWindowRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface WindowChangedEvent {
  processId: number;
}

interface WindowRectResponse {
  ok?: unknown;
  x?: unknown;
  y?: unknown;
  width?: unknown;
  height?: unknown;
}

interface WindowChangedResponse {
  event?: unknown;
  processId?: unknown;
}

const POWERSHELL_SCRIPT = String.raw`
$ErrorActionPreference = "Stop"
Add-Type @'
using System;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public static class PhoneControlWindowApi
{
    [StructLayout(LayoutKind.Sequential)]
    public struct Rect
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct Point
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Message
    {
        public IntPtr HWnd;
        public uint MessageId;
        public IntPtr WParam;
        public IntPtr LParam;
        public uint Time;
        public Point Point;
    }

    private delegate bool EnumWindowsCallback(IntPtr handle, IntPtr parameter);
    private delegate void WinEventDelegate(
        IntPtr hook,
        uint eventType,
        IntPtr window,
        int objectId,
        int childId,
        uint eventThreadId,
        uint eventTime
    );

    private const uint EventSystemMoveSizeStart = 0x000A;
    private const uint EventSystemMoveSizeEnd = 0x000B;
    private const uint EventObjectLocationChange = 0x800B;
    private const uint EventObjectCloaked = 0x8017;
    private const uint EventObjectUncloaked = 0x8018;
    private const int ObjectIdWindow = 0;
    private const int ChildIdSelf = 0;
    private const uint WinEventOutOfContext = 0;
    private const uint WinEventSkipOwnProcess = 2;
    private const uint WindowMessageQuit = 0x0012;
    private const uint PeekMessageNoRemove = 0;

    [DllImport("dwmapi.dll")]
    private static extern int DwmGetWindowAttribute(
        IntPtr hwnd,
        int dwAttribute,
        out int pvAttribute,
        int cbAttribute
    );

    private const int DWMWA_CLOAKED = 14;

    [DllImport("user32.dll")]
    private static extern bool SetProcessDpiAwarenessContext(IntPtr dpiContext);

    [DllImport("user32.dll")]
    private static extern bool SetProcessDPIAware();

    [DllImport("user32.dll")]
    private static extern bool EnumWindows(EnumWindowsCallback callback, IntPtr parameter);

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr handle, out uint processId);

    [DllImport("user32.dll")]
    private static extern bool IsWindowVisible(IntPtr handle);

    [DllImport("user32.dll")]
    private static extern bool IsIconic(IntPtr handle);

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
    private static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);

    [DllImport("user32.dll")]
    private static extern bool GetClientRect(IntPtr handle, out Rect rect);

    [DllImport("user32.dll")]
    private static extern bool ClientToScreen(IntPtr handle, ref Point point);

    [DllImport("user32.dll")]
    private static extern IntPtr SetWinEventHook(
        uint eventMin,
        uint eventMax,
        IntPtr module,
        WinEventDelegate callback,
        uint processId,
        uint threadId,
        uint flags
    );

    [DllImport("user32.dll")]
    private static extern bool UnhookWinEvent(IntPtr hook);

    [DllImport("user32.dll")]
    private static extern int GetMessage(
        out Message message,
        IntPtr window,
        uint minimumMessage,
        uint maximumMessage
    );

    [DllImport("user32.dll")]
    private static extern bool TranslateMessage(ref Message message);

    [DllImport("user32.dll")]
    private static extern IntPtr DispatchMessage(ref Message message);

    [DllImport("user32.dll")]
    private static extern bool PostThreadMessage(
        uint threadId,
        uint message,
        IntPtr wParam,
        IntPtr lParam
    );

    [DllImport("kernel32.dll")]
    private static extern uint GetCurrentThreadId();

    [DllImport("user32.dll")]
    private static extern bool PeekMessage(
        out Message message,
        IntPtr window,
        uint minimumMessage,
        uint maximumMessage,
        uint removeMessage
    );

    private static readonly IntPtr DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = new IntPtr(-4);
    private static readonly IntPtr DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE = new IntPtr(-3);

    private static readonly object OutputLock = new object();
    private static readonly object WatchLock = new object();
    private static Thread WatchThread;
    private static uint WatchThreadId;
    private static int WatchedProcessId;
    private static bool StopRequested;
    private static bool WatchSucceeded;
    private static IntPtr MoveSizeHook;
    private static IntPtr LocationHook;
    private static IntPtr CloakHook;
    private static WinEventDelegate Callback;
    private static ManualResetEvent WatchReady;

    public static void InitializeDpiAwareness()
    {
        try
        {
            if (SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2)) return;
            if (SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE)) return;
        }
        catch {}
        try
        {
            SetProcessDPIAware();
        }
        catch {}
    }

    private static bool IsWindowCloaked(IntPtr handle)
    {
        int cloaked = 0;
        try
        {
            if (DwmGetWindowAttribute(handle, DWMWA_CLOAKED, out cloaked, sizeof(int)) == 0)
            {
                return cloaked != 0;
            }
        }
        catch {}
        return false;
    }

    private static string GetWindowTitle(IntPtr handle)
    {
        StringBuilder sb = new StringBuilder(512);
        if (GetWindowText(handle, sb, 512) > 0)
        {
            return sb.ToString();
        }
        return string.Empty;
    }

    private static void EmitWindowChanged(int processId)
    {
        lock (OutputLock)
        {
            Console.WriteLine("{\"event\":\"window-changed\",\"processId\":" + processId + "}");
            Console.Out.Flush();
        }
    }

    private static void OnWinEvent(
        IntPtr hook,
        uint eventType,
        IntPtr window,
        int objectId,
        int childId,
        uint eventThreadId,
        uint eventTime
    )
    {
        if (window == IntPtr.Zero) return;
        if (
            (eventType == EventObjectLocationChange || eventType == EventObjectCloaked || eventType == EventObjectUncloaked) &&
            (objectId != ObjectIdWindow || childId != ChildIdSelf)
        ) return;

        uint processId;
        GetWindowThreadProcessId(window, out processId);
        if (WatchedProcessId > 0 && processId == (uint)WatchedProcessId)
        {
            EmitWindowChanged(WatchedProcessId);
            return;
        }

        string title = GetWindowTitle(window);
        if (title.IndexOf("Phone Control scrcpy", StringComparison.OrdinalIgnoreCase) >= 0)
        {
            EmitWindowChanged(WatchedProcessId > 0 ? WatchedProcessId : (int)processId);
        }
    }

    private static void WatchLoop(int processId)
    {
        WatchThreadId = GetCurrentThreadId();
        Message initialMessage;
        PeekMessage(
            out initialMessage,
            IntPtr.Zero,
            0,
            0,
            PeekMessageNoRemove
        );

        Callback = OnWinEvent;
        MoveSizeHook = SetWinEventHook(
            EventSystemMoveSizeStart,
            EventSystemMoveSizeEnd,
            IntPtr.Zero,
            Callback,
            0,
            0,
            WinEventOutOfContext | WinEventSkipOwnProcess
        );
        LocationHook = SetWinEventHook(
            EventObjectLocationChange,
            EventObjectLocationChange,
            IntPtr.Zero,
            Callback,
            0,
            0,
            WinEventOutOfContext | WinEventSkipOwnProcess
        );
        CloakHook = SetWinEventHook(
            EventObjectCloaked,
            EventObjectUncloaked,
            IntPtr.Zero,
            Callback,
            0,
            0,
            WinEventOutOfContext | WinEventSkipOwnProcess
        );
        WatchSucceeded = MoveSizeHook != IntPtr.Zero || LocationHook != IntPtr.Zero || CloakHook != IntPtr.Zero;
        WatchReady.Set();

        Message message;
        while (!StopRequested && GetMessage(out message, IntPtr.Zero, 0, 0) > 0)
        {
            TranslateMessage(ref message);
            DispatchMessage(ref message);
        }

        if (MoveSizeHook != IntPtr.Zero) UnhookWinEvent(MoveSizeHook);
        if (LocationHook != IntPtr.Zero) UnhookWinEvent(LocationHook);
        if (CloakHook != IntPtr.Zero) UnhookWinEvent(CloakHook);
        MoveSizeHook = IntPtr.Zero;
        LocationHook = IntPtr.Zero;
        CloakHook = IntPtr.Zero;
        WatchThreadId = 0;
    }

    public static bool WatchProcess(int processId)
    {
        StopWatch();
        lock (WatchLock)
        {
            WatchedProcessId = processId;
            StopRequested = false;
            WatchReady = new ManualResetEvent(false);
            WatchThread = new Thread(() => WatchLoop(processId));
            WatchThread.IsBackground = true;
            try
            {
                WatchThread.SetApartmentState(ApartmentState.STA);
            }
            catch {}
            WatchThread.Start();
        }
        return WatchReady.WaitOne(1000) && WatchSucceeded;
    }

    public static void StopWatch()
    {
        Thread thread;
        lock (WatchLock)
        {
            StopRequested = true;
            thread = WatchThread;
            if (WatchThreadId != 0)
            {
                PostThreadMessage(WatchThreadId, WindowMessageQuit, IntPtr.Zero, IntPtr.Zero);
            }
        }
        if (thread != null && thread != Thread.CurrentThread) thread.Join(1000);
        lock (WatchLock)
        {
            WatchThread = null;
            WatchThreadId = 0;
            WatchedProcessId = 0;
            WatchSucceeded = false;
        }
    }

    private static IntPtr FindVisibleWindow(int processId)
    {
        IntPtr bestHandle = IntPtr.Zero;
        long largestArea = 0;
        int bestMatchPriority = 0;

        EnumWindows((handle, _) =>
        {
            if (!IsWindowVisible(handle) || IsIconic(handle) || IsWindowCloaked(handle)) return true;
            Rect client;
            if (!GetClientRect(handle, out client)) return true;
            long width = client.Right - client.Left;
            long height = client.Bottom - client.Top;
            if (width < 1 || height < 1) return true;
            long area = width * height;

            uint candidateProcessId;
            GetWindowThreadProcessId(handle, out candidateProcessId);
            bool pidMatches = (processId > 0 && candidateProcessId == (uint)processId);

            string title = GetWindowTitle(handle);
            bool titleMatches = title.IndexOf("Phone Control scrcpy", StringComparison.OrdinalIgnoreCase) >= 0;

            int priority = 0;
            if (titleMatches && pidMatches) priority = 3;
            else if (titleMatches) priority = 2;
            else if (pidMatches) priority = 1;

            if (priority > bestMatchPriority || (priority == bestMatchPriority && area > largestArea))
            {
                bestMatchPriority = priority;
                largestArea = area;
                bestHandle = handle;
            }
            return true;
        }, IntPtr.Zero);

        return bestHandle;
    }

    public static bool TryGetClientRect(int processId, out Rect result)
    {
        result = new Rect();
        IntPtr handle = FindVisibleWindow(processId);
        if (handle == IntPtr.Zero || IsWindowCloaked(handle)) return false;
        Rect client;
        if (!GetClientRect(handle, out client)) return false;

        Point origin = new Point { X = client.Left, Y = client.Top };
        if (!ClientToScreen(handle, ref origin)) return false;

        result.Left = origin.X;
        result.Top = origin.Y;
        result.Right = origin.X + (client.Right - client.Left);
        result.Bottom = origin.Y + (client.Bottom - client.Top);
        return result.Right > result.Left && result.Bottom > result.Top;
    }
}
'@

[PhoneControlWindowApi]::InitializeDpiAwareness()

function Write-JsonLine($value) {
    [Console]::WriteLine((ConvertTo-Json -Compress $value))
    [Console]::Out.Flush()
}

while (($line = [Console]::In.ReadLine()) -ne $null) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    try {
        $command = $line.Trim()
        if ($command -match '^watch\s+(\d+)$') {
            $processId = [int]$Matches[1]
            $watchStarted = [PhoneControlWindowApi]::WatchProcess($processId)
            Write-JsonLine([ordered]@{
                ok = $watchStarted
                watching = $processId
            })
            continue
        }
        if ($command -eq 'unwatch') {
            [PhoneControlWindowApi]::StopWatch()
            Write-JsonLine([ordered]@{ ok = $true })
            continue
        }

        if ($command -match '^(?:rect\s+)?(\d+)$') {
            $processId = [int]$Matches[1]
        } else {
            throw "Unknown window helper command."
        }

        $rect = New-Object PhoneControlWindowApi+Rect
        if ([PhoneControlWindowApi]::TryGetClientRect($processId, [ref]$rect)) {
            Write-JsonLine([ordered]@{
                ok = $true
                x = $rect.Left
                y = $rect.Top
                width = $rect.Right - $rect.Left
                height = $rect.Bottom - $rect.Top
            })
        } else {
            Write-JsonLine([ordered]@{ ok = $false })
        }
    } catch {
        Write-JsonLine([ordered]@{ ok = $false })
    }
}
[PhoneControlWindowApi]::StopWatch()
`;

function isFiniteNumber(value: unknown): value is number {
  return typeof value === "number" && Number.isFinite(value);
}

function isPositiveInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value > 0;
}

export function parseClientWindowRect(line: string): ClientWindowRect | null {
  let value: WindowRectResponse;
  try {
    value = JSON.parse(line) as WindowRectResponse;
  } catch {
    return null;
  }
  if (
    value.ok !== true ||
    !isFiniteNumber(value.x) ||
    !isFiniteNumber(value.y) ||
    !isFiniteNumber(value.width) ||
    !isFiniteNumber(value.height) ||
    value.width < 1 ||
    value.height < 1
  ) {
    return null;
  }
  return {
    x: value.x,
    y: value.y,
    width: value.width,
    height: value.height
  };
}

export function parseWindowChangedEvent(line: string): WindowChangedEvent | null {
  let value: WindowChangedResponse;
  try {
    value = JSON.parse(line) as WindowChangedResponse;
  } catch {
    return null;
  }
  if (value.event !== "window-changed" || !isPositiveInteger(value.processId)) {
    return null;
  }
  return { processId: value.processId };
}

function parseWatchResponse(line: string, processId: number): boolean {
  let value: WindowRectResponse & { watching?: unknown };
  try {
    value = JSON.parse(line) as WindowRectResponse & { watching?: unknown };
  } catch {
    return false;
  }
  return value.ok === true && value.watching === processId;
}

interface PendingRequest {
  command: string;
  parse: (line: string) => unknown;
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
}

function powershellPath(): string {
  const systemRoot = process.env.SystemRoot;
  return systemRoot
    ? join(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
    : "powershell.exe";
}

export class Win32ClientWindowRectProvider {
  readonly #process: ChildProcessWithoutNullStreams;
  readonly #queue: PendingRequest[] = [];
  readonly #windowChangedListeners = new Set<(event: WindowChangedEvent) => void>();
  #active: PendingRequest | undefined;
  #buffer = "";
  #closed = false;

  public constructor() {
    this.#process = spawn(
      powershellPath(),
      ["-NoLogo", "-NoProfile", "-NonInteractive", "-Command", POWERSHELL_SCRIPT],
      { windowsHide: true, stdio: ["pipe", "pipe", "pipe"] }
    );
    this.#process.stdout.setEncoding("utf8");
    this.#process.stderr.setEncoding("utf8");
    this.#process.stderr.on("data", (chunk: string | Buffer) => {
      const message = Buffer.isBuffer(chunk) ? chunk.toString("utf8") : chunk;
      if (message.trim()) {
        console.error(`[phone-control-viewer] window helper: ${message.trim()}`);
      }
    });
    this.#process.stdout.on("data", (chunk: string | Buffer) => {
      this.#buffer += Buffer.isBuffer(chunk) ? chunk.toString("utf8") : chunk;
      const lines = this.#buffer.split(/\r?\n/);
      this.#buffer = lines.pop() ?? "";
      for (const line of lines) this.#resolveLine(line);
    });
    this.#process.on("error", (error) => {
      this.#closed = true;
      this.#fail(error);
    });
    this.#process.on("exit", (code, signal) => {
      if (!this.#closed) {
        this.#closed = true;
        this.#fail(new Error(`Win32 window helper exited (${code ?? signal ?? "unknown"}).`));
      }
    });
  }

  public getClientRect(processId: number): Promise<ClientWindowRect | null> {
    return this.#enqueue(`rect ${processId}`, (line) => parseClientWindowRect(line));
  }

  public watchProcess(processId: number): Promise<boolean> {
    return this.#enqueue(`watch ${processId}`, (line) => parseWatchResponse(line, processId));
  }

  public onWindowChanged(listener: (event: WindowChangedEvent) => void): () => void {
    this.#windowChangedListeners.add(listener);
    return () => this.#windowChangedListeners.delete(listener);
  }

  public close(): void {
    if (this.#closed) return;
    this.#closed = true;
    this.#fail(new Error("Win32 window helper closed."));
    this.#process.stdin.end();
    if (!this.#process.killed) this.#process.kill();
  }

  #enqueue<T>(command: string, parse: (line: string) => T): Promise<T> {
    if (this.#closed) return Promise.resolve(parse('{"ok":false}'));
    return new Promise<T>((resolve, reject) => {
      this.#queue.push({
        command,
        parse,
        resolve: (value) => resolve(value as T),
        reject
      });
      this.#pump();
    });
  }

  #pump(): void {
    if (this.#closed || this.#active || this.#queue.length === 0) return;
    this.#active = this.#queue.shift();
    if (!this.#active) return;
    try {
      this.#process.stdin.write(`${this.#active.command}\n`);
    } catch (error) {
      this.#fail(error instanceof Error ? error : new Error(String(error)));
    }
  }

  #resolveLine(line: string): void {
    const event = parseWindowChangedEvent(line);
    if (event) {
      for (const listener of this.#windowChangedListeners) {
        try {
          listener(event);
        } catch (error) {
          console.error(
            `[phone-control-viewer] window-change listener failed: ${
              error instanceof Error ? error.message : String(error)
            }`
          );
        }
      }
      return;
    }

    if (!this.#active) return;
    const request = this.#active;
    this.#active = undefined;
    request.resolve(request.parse(line));
    this.#pump();
  }

  #fail(error: Error): void {
    if (this.#active) {
      this.#active.reject(error);
      this.#active = undefined;
    }
    while (this.#queue.length > 0) {
      this.#queue.shift()?.reject(error);
    }
  }
}
