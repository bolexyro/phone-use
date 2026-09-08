package com.phonecontrol.assistant.developer;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native display backend used by the DHD shell-UID maintenance daemon.
 *
 * The daemon process owns both the virtual display callback token and the
 * encoder input surface. Only encoded AVC packets cross the authenticated
 * loopback stream; an Android Surface is never serialized through TCP.
 */
final class DhdNativeDisplayService implements Closeable {
    static final String COMMAND = "dhd-display";
    static final String CREATED_TYPE = "dhd_display_created";
    static final String CODEC_MIME = "video/avc";
    static final int STREAM_MAGIC = 0x44485631; // DHV1
    static final int STREAM_VERSION = 1;
    static final int MAX_SESSIONS = 2;
    static final int MAX_STREAM_PACKET_BYTES = 4 * 1024 * 1024;
    /** Keep only a short burst, but never resume from a partial AVC GOP. */
    static final int MAX_STREAM_QUEUE_PACKETS = 8;
    static final long COMMAND_TIMEOUT_MS = 15_000L;
    static final long LAUNCH_VERIFY_TIMEOUT_MS = 2_500L;

    private static final String SHELL_PACKAGE = "com.android.shell";
    private static final Pattern SESSION_KEY_PATTERN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}");
    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+");
    private static final Pattern DISPLAY_INFO_ID_PATTERN = Pattern.compile(
            "\\b(?:Display\\s+id|displayId|mDisplayId)\\s*[:=]?\\s*(\\d+)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SURFACE_FLINGER_DISPLAY_PATTERN = Pattern.compile(
            "^\\s*(?:Virtual\\s+)?Display\\s+(\\d+)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SURFACE_FLINGER_VIRTUAL_HEADER_PATTERN = Pattern.compile(
            "^\\s*Display\\s+(\\d+)\\s+\\(virtual",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTIVITY_DISPLAY_HEADER_PATTERN = Pattern.compile(
            "^\\s*Display\\s*#(\\d+)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTIVITY_FOCUS_MARKER_PATTERN = Pattern.compile(
            "\\b(?:topResumedActivity|mResumedActivity|mCurrentFocus|mFocusedApp)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, DisplaySession> sessions = new HashMap<>();
    private final Object lock = new Object();

    /** Result shape kept separate from the maintenance daemon's shell result. */
    static final class CommandResult {
        final int exitCode;
        final boolean timedOut;
        final byte[] stdout;
        final String stderr;

        CommandResult(int exitCode, boolean timedOut, byte[] stdout, String stderr) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.stdout = stdout == null ? new byte[0] : stdout;
            this.stderr = stderr == null ? "" : stderr;
        }

        static CommandResult success(byte[] stdout) {
            return new CommandResult(0, false, stdout, "");
        }

        static CommandResult failure(String stderr) {
            return new CommandResult(DhdMaintenanceProtocol.EXIT_CODE_UNAVAILABLE, false,
                    new byte[0], stderr);
        }
    }

    CommandResult execute(List<String> command, boolean binaryOutput) {
        if (command == null || command.size() < 2 || !COMMAND.equals(command.get(0))) {
            return CommandResult.failure("DHD display command is invalid.");
        }
        String operation = command.get(1);
        try {
            switch (operation) {
                case "create":
                    return create(command);
                case "attach":
                    return attach(command);
                case "detach":
                    return detach(command);
                case "capture":
                    return capture(command, binaryOutput);
                case "close":
                    return closeSession(command);
                case "close-all":
                    return closeAll();
                default:
                    return CommandResult.failure("DHD display operation is unsupported: " + operation);
            }
        } catch (Throwable error) {
            return CommandResult.failure("DHD display operation failed: " + safeMessage(error));
        }
    }

    @Override
    public void close() {
        DisplaySession[] active;
        synchronized (lock) {
            active = sessions.values().toArray(new DisplaySession[0]);
            sessions.clear();
        }
        for (DisplaySession session : active) {
            session.close();
        }
    }

    private CommandResult create(List<String> command) throws Exception {
        if (command.size() != 9) {
            return CommandResult.failure(
                    "dhd-display create requires sessionKey, packageName, width, height, densityDpi, frameRate, bitRate.");
        }
        String sessionKey = command.get(2);
        String packageName = command.get(3);
        if (!SESSION_KEY_PATTERN.matcher(sessionKey).matches()) {
            return CommandResult.failure("DHD display session key is invalid.");
        }
        if (!PACKAGE_PATTERN.matcher(packageName).matches()) {
            return CommandResult.failure("DHD display package name is invalid.");
        }
        int width = boundedInt(command.get(4), 320, 2_160, "width");
        int height = boundedInt(command.get(5), 320, 3_840, "height");
        int densityDpi = boundedInt(command.get(6), 120, 640, "densityDpi");
        int frameRate = boundedInt(command.get(7), 1, 60, "frameRate");
        int bitRate = boundedInt(command.get(8), 128_000, 20_000_000, "bitRate");

        synchronized (lock) {
            if (sessions.containsKey(sessionKey)) {
                return CommandResult.failure("DHD display session is already active.");
            }
            if (sessions.size() >= MAX_SESSIONS) {
                return CommandResult.failure("DHD display session limit reached.");
            }
        }

        DisplaySession session = new DisplaySession(
                sessionKey, packageName, width, height, densityDpi, frameRate, bitRate);
        try {
            session.start();
            synchronized (lock) {
                if (sessions.containsKey(sessionKey)) {
                    throw new IOException("DHD display session was created concurrently.");
                }
                sessions.put(sessionKey, session);
            }
            return CommandResult.success(session.createdJson());
        } catch (Throwable error) {
            session.close();
            return CommandResult.failure("DHD display creation failed: " + safeMessage(error));
        }
    }

    private CommandResult attach(List<String> command) {
        if (command.size() != 3) return CommandResult.failure("dhd-display attach requires sessionKey.");
        DisplaySession session = find(command.get(2));
        if (session == null) return CommandResult.failure("DHD display session is not active.");
        session.allowStreamClient();
        return CommandResult.success("{\"type\":\"dhd_display_attached\"}".getBytes(StandardCharsets.UTF_8));
    }

    private CommandResult detach(List<String> command) {
        if (command.size() != 3) return CommandResult.failure("dhd-display detach requires sessionKey.");
        DisplaySession session = find(command.get(2));
        if (session == null) return CommandResult.success(new byte[0]);
        session.detachStreamClient();
        return CommandResult.success(new byte[0]);
    }

    private CommandResult capture(List<String> command, boolean binaryOutput) throws Exception {
        if (command.size() != 3) return CommandResult.failure("dhd-display capture requires sessionKey.");
        if (!binaryOutput) return CommandResult.failure("DHD display capture requires binary output mode.");
        DisplaySession session = find(command.get(2));
        if (session == null) return CommandResult.failure("DHD display session is not active.");
        byte[] png = captureDisplay(session.displayId, "DHD " + session.sessionKey);
        return CommandResult.success(png);
    }

    private CommandResult closeSession(List<String> command) {
        if (command.size() != 3) return CommandResult.failure("dhd-display close requires sessionKey.");
        DisplaySession session;
        synchronized (lock) {
            session = sessions.remove(command.get(2));
        }
        if (session != null) session.close();
        return CommandResult.success(new byte[0]);
    }

    private CommandResult closeAll() {
        DisplaySession[] active;
        synchronized (lock) {
            active = sessions.values().toArray(new DisplaySession[0]);
            sessions.clear();
        }
        for (DisplaySession session : active) session.close();
        return CommandResult.success(new byte[0]);
    }

    private DisplaySession find(String sessionKey) {
        synchronized (lock) {
            return sessions.get(sessionKey);
        }
    }

    private static int boundedInt(String value, int min, int max, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new IllegalArgumentException(name + " is out of range.");
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " is not an integer.");
        }
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null &&
                (current.getMessage() == null || current.getMessage().isEmpty())) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isEmpty() ? current.getClass().getSimpleName() : message;
    }

    private static byte[] captureDisplay(int logicalDisplayId, String displayName) throws Exception {
        if (logicalDisplayId <= 0) throw new IOException("The default display is not a task display.");
        String displayInfo = runText(new String[]{"/system/bin/cmd", "display", "get-displays"});
        String uniqueId = findLogicalUniqueId(displayInfo, logicalDisplayId);
        String sf = runText(new String[]{"/system/bin/dumpsys", "SurfaceFlinger", "--display-id"});
        String sfId = findSurfaceFlingerId(sf, logicalDisplayId, uniqueId);
        if (sfId == null) {
            String displays = runText(new String[]{"/system/bin/dumpsys", "SurfaceFlinger", "--displays"});
            sfId = findSurfaceFlingerId(displays, logicalDisplayId, uniqueId);
            if (sfId == null) {
                sfId = findUniqueSurfaceFlingerVirtualDisplayId(displays, displayName);
            }
        }
        if (sfId == null) {
            throw new IOException("SurfaceFlinger did not expose a capture id bound to logical display " + logicalDisplayId + ".");
        }
        ProcessResult result = run(new String[]{"/system/bin/screencap", "-d", sfId, "-p"}, COMMAND_TIMEOUT_MS);
        if (result.exitCode != 0 || result.stdout.length == 0) {
            throw new IOException("DHD display capture failed: " + result.stderr);
        }
        return result.stdout;
    }

    private static String findLogicalUniqueId(String output, int logicalDisplayId) {
        if (output == null) return null;
        boolean inRequestedDisplay = false;
        for (String line : output.split("\\r?\\n")) {
            Integer id = parseDisplayId(line);
            if (id != null) {
                inRequestedDisplay = id == logicalDisplayId;
            }
            if (!inRequestedDisplay) continue;
            String uniqueId = parseUniqueId(line);
            if (uniqueId != null) return uniqueId;
            if (id != null && id != logicalDisplayId) return null;
        }
        return null;
    }

    private static String parseUniqueId(String line) {
        Matcher quoted = Pattern.compile(
                "\\buniqueId\\s*[\"'=:\\s]+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
                .matcher(line);
        if (quoted.find()) return quoted.group(1);
        Matcher unquoted = Pattern.compile(
                "\\buniqueId\\s*[\"'=:\\s]+([^,}\\s]+)", Pattern.CASE_INSENSITIVE)
                .matcher(line);
        return unquoted.find() ? unquoted.group(1) : null;
    }

    private static String findUniqueSurfaceFlingerVirtualDisplayId(
            String output,
            String expectedDisplayName
    ) {
        if (output == null || expectedDisplayName == null || expectedDisplayName.isEmpty()) return null;
        Set<String> candidates = new HashSet<>();
        String currentVirtualId = null;
        for (String line : output.split("\\r?\\n")) {
            Matcher singleLine = Pattern.compile(
                    "^\\s*Display\\s+(\\d+)\\b.*?(?:Virtual\\s+display|DisplayDevice)"
                            + ".*?(?:displayName|name)=?\\\"([^\\\"]+)\\\"",
                    Pattern.CASE_INSENSITIVE).matcher(line);
            if (singleLine.find()) {
                if (expectedDisplayName.equalsIgnoreCase(singleLine.group(2))) {
                    candidates.add(singleLine.group(1));
                }
                currentVirtualId = null;
                continue;
            }
            Matcher header = Pattern.compile(
                    "^\\s*(?:Virtual\\s+)?Display\\s+(\\d+)\\b.*(?:virtual)?",
                    Pattern.CASE_INSENSITIVE).matcher(line);
            if (header.find() && line.toLowerCase(Locale.ROOT).contains("virtual")) {
                currentVirtualId = header.group(1);
                Matcher inlineName = Pattern.compile(
                        "\\b(?:displayName|name)=?\\\"([^\\\"]+)\\\"",
                        Pattern.CASE_INSENSITIVE).matcher(line);
                if (inlineName.find()) {
                    if (expectedDisplayName.equalsIgnoreCase(inlineName.group(1))) {
                        candidates.add(currentVirtualId);
                    }
                    currentVirtualId = null;
                }
                continue;
            }
            if (currentVirtualId != null) {
                Matcher name = Pattern.compile(
                        "\\b(?:displayName|name)=?\\\"([^\\\"]+)\\\"",
                        Pattern.CASE_INSENSITIVE).matcher(line);
                if (name.find()) {
                    if (expectedDisplayName.equalsIgnoreCase(name.group(1))) {
                        candidates.add(currentVirtualId);
                    }
                    currentVirtualId = null;
                } else if (SURFACE_FLINGER_DISPLAY_PATTERN.matcher(line).find()) {
                    currentVirtualId = null;
                }
            }
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }

    private static String findSurfaceFlingerId(String output, int logicalDisplayId, String uniqueId) {
        if (output == null || uniqueId == null || uniqueId.isEmpty()) return null;
        String currentDisplay = null;
        String uniqueMatch = null;
        for (String line : output.split("\\r?\\n")) {
            Matcher display = SURFACE_FLINGER_DISPLAY_PATTERN.matcher(line);
            if (display.find()) {
                currentDisplay = display.group(1);
            }
            if (currentDisplay != null && line.contains(uniqueId)) {
                if (uniqueMatch != null && !uniqueMatch.equals(currentDisplay)) return null;
                uniqueMatch = currentDisplay;
            }
        }
        return uniqueMatch;
    }

    private static String findSurfaceFlingerLayerStack(String output, int logicalDisplayId) {
        if (output == null) return null;
        String current = null;
        for (String line : output.split("\\r?\\n")) {
            Matcher display = SURFACE_FLINGER_VIRTUAL_HEADER_PATTERN.matcher(line);
            if (display.find()) {
                current = display.group(1);
                continue;
            }
            if (current != null) {
                Matcher stack = Pattern.compile("layerFilter=\\{layerStack=(\\d+)\\b",
                        Pattern.CASE_INSENSITIVE).matcher(line);
                if (stack.find()) {
                    if (stack.group(1).equals(Integer.toString(logicalDisplayId))) return current;
                    current = null;
                } else if (line.matches("^\\s*(?:Virtual\\s+)?Display\\s+\\d+.*")) {
                    current = null;
                }
            }
        }
        return null;
    }

    private static Integer parseDisplayId(String line) {
        Matcher display = DISPLAY_INFO_ID_PATTERN.matcher(line);
        return display.find() ? Integer.valueOf(display.group(1)) : null;
    }

    private static String runText(String[] command) throws Exception {
        ProcessResult result = run(command, COMMAND_TIMEOUT_MS);
        if (result.exitCode != 0) throw new IOException(result.stderr);
        return new String(result.stdout, StandardCharsets.UTF_8);
    }

    private static ProcessResult run(String[] command, long timeoutMs) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        Collector stdout = new Collector(process.getInputStream(), DhdMaintenanceProtocol.MAX_OUTPUT_BYTES);
        Collector stderr = new Collector(process.getErrorStream(), 256 * 1024);
        Thread stdoutThread = new Thread(stdout, "dhd-display-capture-out");
        Thread stderrThread = new Thread(stderr, "dhd-display-capture-err");
        stdoutThread.start();
        stderrThread.start();
        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) process.destroyForcibly();
        stdoutThread.join(1_000L);
        stderrThread.join(1_000L);
        return new ProcessResult(
                finished ? process.exitValue() : DhdMaintenanceProtocol.EXIT_CODE_UNAVAILABLE,
                stdout.bytes(), stderr.text());
    }

    private static final class ProcessResult {
        final int exitCode;
        final byte[] stdout;
        final String stderr;

        ProcessResult(int exitCode, byte[] stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static final class Collector implements Runnable {
        private final InputStream input;
        private final int maxBytes;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private volatile boolean overflowed;

        Collector(InputStream input, int maxBytes) {
            this.input = input;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > maxBytes) {
                        overflowed = true;
                        continue;
                    }
                    output.write(buffer, 0, read);
                }
            } catch (IOException ignored) {
                // The process can close its pipe while a timeout is terminating it.
            }
        }

        byte[] bytes() {
            return output.toByteArray();
        }

        String text() {
            return new String(bytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static final class DisplaySession implements Closeable {
        private final String sessionKey;
        private final String packageName;
        private final int width;
        private final int height;
        private final int densityDpi;
        private final int frameRate;
        private final int bitRate;
        private final String streamToken = newToken();
        private final ArrayDeque<EncodedPacket> packets = new ArrayDeque<>(MAX_STREAM_QUEUE_PACKETS);
        private final Object streamLock = new Object();
        private final Object codecLock = new Object();
        private final ExecutorService executor = Executors.newFixedThreadPool(2);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile boolean streamClientAllowed;
        private volatile Socket streamClient;
        /** True until the queue contains a decodable IDR boundary. */
        private boolean awaitingKeyFrame = true;
        private ServerSocket streamServer;
        private MediaCodec encoder;
        private Surface encoderSurface;
        private DisplayManagerBridge displayBridge;
        private int displayId = -1;
        private volatile MediaFormat outputFormat;

        DisplaySession(String sessionKey, String packageName, int width, int height,
                       int densityDpi, int frameRate, int bitRate) {
            this.sessionKey = sessionKey;
            this.packageName = packageName;
            this.width = width;
            this.height = height;
            this.densityDpi = densityDpi;
            this.frameRate = frameRate;
            this.bitRate = bitRate;
        }

        void start() throws Exception {
            MediaFormat format = MediaFormat.createVideoFormat(CODEC_MIME, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfoCompat.COLOR_FORMAT_SURFACE);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(CODEC_MIME);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderSurface = encoder.createInputSurface();
            encoder.start();

            displayBridge = new DisplayManagerBridge();
            displayId = displayBridge.createVirtualDisplay(
                    "DHD " + sessionKey, width, height, densityDpi, encoderSurface);
            if (displayId <= 0) throw new IOException("Android created an invalid task display id.");

            streamServer = new ServerSocket();
            streamServer.setReuseAddress(true);
            streamServer.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            executor.submit(this::drainEncoder);
            executor.submit(this::serveStream);
            launchTarget();
        }

        byte[] createdJson() {
            String json = "{\"type\":\"" + CREATED_TYPE + "\"" +
                    ",\"sessionKey\":\"" + escape(sessionKey) + "\"" +
                    ",\"packageName\":\"" + escape(packageName) + "\"" +
                    ",\"displayId\":" + displayId +
                    ",\"width\":" + width +
                    ",\"height\":" + height +
                    ",\"densityDpi\":" + densityDpi +
                    ",\"frameRate\":" + frameRate +
                    ",\"bitRate\":" + bitRate +
                    ",\"streamPort\":" + streamServer.getLocalPort() +
                    ",\"streamToken\":\"" + escape(streamToken) + "\"" +
                    ",\"codecMime\":\"" + CODEC_MIME + "\"}";
            return json.getBytes(StandardCharsets.UTF_8);
        }

        void allowStreamClient() {
            streamClientAllowed = true;
        }

        void detachStreamClient() {
            streamClientAllowed = false;
            synchronized (streamLock) {
                closeQuietly(streamClient);
                streamClient = null;
                streamLock.notifyAll();
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (streamLock) {
                closeQuietly(streamClient);
                closeQuietly(streamServer);
                streamClient = null;
                streamServer = null;
                streamLock.notifyAll();
            }
            synchronized (codecLock) {
                if (encoder != null) {
                    try { encoder.stop(); } catch (Throwable ignored) {}
                    try { encoder.release(); } catch (Throwable ignored) {}
                    encoder = null;
                }
                if (encoderSurface != null) {
                    closeQuietly(encoderSurface);
                    encoderSurface = null;
                }
            }
            if (displayBridge != null && displayId > 0) displayBridge.releaseVirtualDisplay();
            displayId = -1;
            executor.shutdownNow();
        }

        private void launchTarget() throws Exception {
            String component = resolveLaunchComponent();
            String[] command = new String[]{
                    "/system/bin/am", "start", "-W", "--display", Integer.toString(displayId),
                    "-f", "0x18080000", "-n", component,
            };
            ProcessResult result = run(command, COMMAND_TIMEOUT_MS);
            if (result.exitCode != 0 || result.stderr.toLowerCase(Locale.ROOT).contains("error") ||
                    new String(result.stdout, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT).contains("error:")) {
                throw new IOException("Could not launch " + packageName + " on display " + displayId +
                        ": " + diagnostic(result));
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LAUNCH_VERIFY_TIMEOUT_MS);
            while (System.nanoTime() < deadline) {
                if (focusedPackageOnDisplay(displayId, packageName)) return;
                Thread.sleep(100L);
            }
            throw new IOException("Android did not verify " + packageName + " on display " + displayId + ".");
        }

        private String resolveLaunchComponent() throws Exception {
            ProcessResult result = run(new String[]{
                    "/system/bin/cmd", "package", "resolve-activity", "--brief",
                    "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.LAUNCHER",
                    packageName,
            }, COMMAND_TIMEOUT_MS);
            if (result.exitCode != 0) {
                throw new IOException("Could not resolve a launcher for " + packageName +
                        ": " + diagnostic(result));
            }
            String output = new String(result.stdout, StandardCharsets.UTF_8);
            Pattern componentPattern = Pattern.compile(
                    "(?m)^\\s*(" + Pattern.quote(packageName) + "/[^\\s]+)\\s*$");
            Matcher match = componentPattern.matcher(output);
            if (!match.find()) {
                throw new IOException("No launcher activity was resolved for " + packageName +
                        ": " + output.trim());
            }
            return match.group(1);
        }

        private String diagnostic(ProcessResult result) {
            String stderr = result.stderr == null ? "" : result.stderr.trim();
            String stdout = new String(result.stdout, StandardCharsets.UTF_8).trim();
            if (!stderr.isEmpty() && !stdout.isEmpty()) return stderr + " | " + stdout;
            if (!stderr.isEmpty()) return stderr;
            if (!stdout.isEmpty()) return stdout;
            return "exit " + result.exitCode;
        }

        private boolean focusedPackageOnDisplay(int expectedDisplayId, String expectedPackage) {
            try {
                String output = runText(new String[]{"/system/bin/dumpsys", "activity", "activities"});
                int currentDisplay = -1;
                Pattern packagePattern = Pattern.compile(
                        "(?<![A-Za-z0-9_])" + Pattern.quote(expectedPackage) + "(?:/|\\b)");
                for (String line : output.split("\\r?\\n")) {
                    Matcher display = ACTIVITY_DISPLAY_HEADER_PATTERN.matcher(line);
                    if (display.find()) currentDisplay = Integer.parseInt(display.group(1));
                    if (currentDisplay == expectedDisplayId &&
                            ACTIVITY_FOCUS_MARKER_PATTERN.matcher(line).find() &&
                            packagePattern.matcher(line).find()) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // Verification failure is handled by the caller as unsafe.
            }
            return false;
        }

        private void drainEncoder() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (!closed.get()) {
                    EncodedPacket packet = null;
                    boolean endOfStream = false;
                    boolean formatChanged = false;
                    synchronized (codecLock) {
                        MediaCodec codec = encoder;
                        if (codec == null) return;
                        int index = codec.dequeueOutputBuffer(info, 100_000L);
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            outputFormat = codec.getOutputFormat();
                            formatChanged = true;
                        } else if (index >= 0) {
                            ByteBuffer buffer = codec.getOutputBuffer(index);
                            if (buffer != null && info.size > 0) {
                                ByteBuffer duplicate = buffer.duplicate();
                                duplicate.position(info.offset);
                                duplicate.limit(info.offset + info.size);
                                byte[] bytes = new byte[info.size];
                                duplicate.get(bytes);
                                packet = new EncodedPacket(info.flags, info.presentationTimeUs, bytes);
                            }
                            codec.releaseOutputBuffer(index, false);
                            endOfStream = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        }
                    }
                    if (formatChanged) {
                        synchronized (streamLock) { streamLock.notifyAll(); }
                        continue;
                    }
                    if (packet != null) enqueue(packet);
                    if (endOfStream) return;
                }
            } catch (Throwable ignored) {
                // The task receives a stream EOF and can report the failed preview.
            }
        }

        private void enqueue(EncodedPacket packet) {
            if (packet.bytes.length == 0 || packet.bytes.length > MAX_STREAM_PACKET_BYTES) return;
            boolean requestSyncFrame = false;
            boolean accepted = true;
            synchronized (streamLock) {
                if (packets.size() >= MAX_STREAM_QUEUE_PACKETS) {
                    // Dropping an arbitrary AVC packet can discard a P-frame
                    // that later frames reference. The decoder then renders a
                    // blank surface until the next IDR. Reset the queue at a
                    // GOP boundary and request a fresh sync frame instead.
                    packets.clear();
                    awaitingKeyFrame = true;
                    requestSyncFrame = true;
                }
                if (awaitingKeyFrame) {
                    if ((packet.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) {
                        // Keep the queue empty until the encoder supplies the
                        // requested IDR. Do not return before the sync-frame
                        // request below; otherwise an overflow would leave
                        // the decoder waiting forever on the old GOP.
                        accepted = false;
                    } else {
                        awaitingKeyFrame = false;
                    }
                }
                if (accepted) {
                    packets.addLast(packet);
                    streamLock.notifyAll();
                }
            }
            if (requestSyncFrame) {
                try {
                    requestSyncFrame();
                } catch (Throwable ignored) {
                    // The next encoder keyframe still provides a safe
                    // recovery point if this best-effort request is rejected.
                }
            }
            if (!accepted) return;
        }

        private void serveStream() {
            while (!closed.get()) {
                Socket client = null;
                try {
                    ServerSocket server = streamServer;
                    if (server == null) return;
                    client = server.accept();
                    client.setTcpNoDelay(true);
                    // Authenticate the client before exposing the codec
                    // configuration or a single video packet. A server-only
                    // token in the response would allow any local process to
                    // read the preview stream.
                    client.setSoTimeout(2_000);
                    DataInputStream input = new DataInputStream(client.getInputStream());
                    if (!authenticateClient(input)) {
                        closeQuietly(client);
                        continue;
                    }
                    client.setSoTimeout(30_000);
                    synchronized (streamLock) {
                        if (!streamClientAllowed) {
                            closeQuietly(client);
                            continue;
                        }
                        closeQuietly(streamClient);
                        streamClient = client;
                    }
                    writeStream(client);
                } catch (Throwable ignored) {
                    if (closed.get()) return;
                } finally {
                    synchronized (streamLock) {
                        if (streamClient == client) streamClient = null;
                    }
                    closeQuietly(client);
                }
            }
        }

        private boolean authenticateClient(DataInputStream input) throws IOException {
            if (input.readInt() != STREAM_MAGIC || input.readInt() != STREAM_VERSION) return false;
            int length = input.readInt();
            if (length < 0 || length > 128) return false;
            byte[] bytes = new byte[length];
            input.readFully(bytes);
            return DhdMaintenanceProtocol.tokensEqual(
                    streamToken,
                    new String(bytes, StandardCharsets.UTF_8));
        }

        private void writeStream(Socket client) throws Exception {
            DataOutputStream output = new DataOutputStream(client.getOutputStream());
            synchronized (streamLock) {
                // A reconnecting decoder cannot safely start in the middle of
                // an old GOP. Drop stale packets and wait for a fresh IDR.
                packets.clear();
                awaitingKeyFrame = true;
            }
            // Reset the queue before asking the encoder for an IDR. That
            // ordering prevents a keyframe produced during the request from
            // being discarded by the reconnect cleanup above.
            requestSyncFrame();
            MediaFormat format;
            synchronized (streamLock) {
                while (!closed.get() && outputFormat == null) streamLock.wait(100L);
                format = outputFormat;
            }
            if (format == null) throw new IOException("Encoder format did not become available.");
            output.writeInt(STREAM_MAGIC);
            output.writeInt(STREAM_VERSION);
            writeString(output, streamToken);
            writeString(output, CODEC_MIME);
            output.writeInt(width);
            output.writeInt(height);
            writeBuffer(output, format, "csd-0");
            writeBuffer(output, format, "csd-1");
            output.flush();

            boolean keyFrameSeen = false;
            while (!closed.get() && !client.isClosed()) {
                EncodedPacket packet;
                synchronized (streamLock) {
                    while (!closed.get() && packets.isEmpty() && streamClient == client) streamLock.wait(250L);
                    if (closed.get() || streamClient != client) return;
                    packet = packets.pollFirst();
                }
                if (packet == null) continue;
                if (!keyFrameSeen) {
                    if ((packet.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) continue;
                    keyFrameSeen = true;
                }
                output.writeInt(packet.flags);
                output.writeLong(packet.presentationTimeUs);
                output.writeInt(packet.bytes.length);
                output.write(packet.bytes);
                output.flush();
            }
        }

        private void requestSyncFrame() throws IOException {
            synchronized (codecLock) {
                MediaCodec codec = encoder;
                if (codec == null || closed.get()) throw new IOException("DHD display encoder is closed.");
                try {
                    android.os.Bundle parameters = new android.os.Bundle();
                    parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                    codec.setParameters(parameters);
                } catch (Throwable error) {
                    throw new IOException("DHD display encoder could not request a key frame.", error);
                }
            }
        }

        private static void writeString(DataOutputStream output, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 128) throw new IOException("DHD display stream string is too long.");
            output.writeInt(bytes.length);
            output.write(bytes);
        }

        private static void writeBuffer(DataOutputStream output, MediaFormat format, String key) throws IOException {
            ByteBuffer source = format.getByteBuffer(key);
            if (source == null) {
                output.writeInt(-1);
                return;
            }
            ByteBuffer buffer = source.duplicate();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            if (bytes.length > 1 << 20) throw new IOException("DHD codec config is too large.");
            output.writeInt(bytes.length);
            output.write(bytes);
        }

        private static String escape(String text) {
            return text.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static void closeQuietly(Closeable closeable) {
            if (closeable == null) return;
            try { closeable.close(); } catch (Throwable ignored) {}
        }

        private static void closeQuietly(Surface surface) {
            if (surface == null) return;
            try { surface.release(); } catch (Throwable ignored) {}
        }

        private static final class EncodedPacket {
            final int flags;
            final long presentationTimeUs;
            final byte[] bytes;

            EncodedPacket(int flags, long presentationTimeUs, byte[] bytes) {
                this.flags = flags;
                this.presentationTimeUs = presentationTimeUs;
                this.bytes = bytes;
            }
        }
    }

    /** Compatibility constants kept out of the public SDK surface. */
    private static final class MediaCodecInfoCompat {
        static final int COLOR_FORMAT_SURFACE = 0x7F000789;

        private MediaCodecInfoCompat() {}
    }

    /** Reflection bridge keeps hidden display-manager classes out of the app's compile API. */
    private static final class DisplayManagerBridge {
        private final Object service;
        private final Object callback;
        private final android.os.Binder callbackBinder;
        private final Method releaseMethod;
        private final Object windowService;
        private final Method setDisplayImePolicyMethod;
        private int displayId = -1;

        DisplayManagerBridge() throws Exception {
            if (Build.VERSION.SDK_INT != 36) {
                throw new UnsupportedOperationException(
                        "DHD native virtual displays currently require Android API 36; got "
                                + Build.VERSION.SDK_INT);
            }
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getMethod("getService", String.class);
            android.os.IBinder binder = (android.os.IBinder) getService.invoke(null, "display");
            if (binder == null) throw new IOException("Android display service is unavailable.");
            Class<?> stub = Class.forName("android.hardware.display.IDisplayManager$Stub");
            service = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            Class<?> displayManager = Class.forName("android.hardware.display.IDisplayManager");
            Class<?> callbackType = Class.forName("android.hardware.display.IVirtualDisplayCallback");
            callbackBinder = new android.os.Binder();
            callback = Proxy.newProxyInstance(
                    callbackType.getClassLoader(), new Class<?>[]{callbackType},
                    (proxy, method, args) -> {
                        if ("asBinder".equals(method.getName())) return callbackBinder;
                        if ("toString".equals(method.getName())) return "DhdVirtualDisplayCallback";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        return null;
            });
            releaseMethod = displayManager.getMethod("releaseVirtualDisplay", callbackType);

            android.os.IBinder windowBinder = (android.os.IBinder) getService.invoke(null, "window");
            if (windowBinder == null) throw new IOException("Android window service is unavailable.");
            Class<?> windowStub = Class.forName("android.view.IWindowManager$Stub");
            windowService = windowStub.getMethod("asInterface", android.os.IBinder.class)
                    .invoke(null, windowBinder);
            Class<?> windowManager = Class.forName("android.view.IWindowManager");
            setDisplayImePolicyMethod = windowManager.getMethod(
                    "setDisplayImePolicy", int.class, int.class);
        }

        int createVirtualDisplay(String name, int width, int height, int densityDpi, Surface surface)
                throws Exception {
            Class<?> configBuilder = Class.forName("android.hardware.display.VirtualDisplayConfig$Builder");
            Constructor<?> constructor = configBuilder.getConstructor(
                    String.class, int.class, int.class, int.class);
            Object builder = constructor.newInstance(name, width, height, densityDpi);
            configBuilder.getMethod("setSurface", Surface.class).invoke(builder, surface);
            configBuilder.getMethod("setFlags", int.class).invoke(builder, displayFlags());
            Object config = configBuilder.getMethod("build").invoke(builder);
            Class<?> configType = Class.forName("android.hardware.display.VirtualDisplayConfig");
            Class<?> callbackType = Class.forName("android.hardware.display.IVirtualDisplayCallback");
            Class<?> projectionType = Class.forName("android.media.projection.IMediaProjection");
            // API 36's binder contract is exactly
            // (VirtualDisplayConfig, IVirtualDisplayCallback, IMediaProjection, String).
            // The five-argument overload belongs to DisplayManagerInternal and is
            // not exposed by the display binder. Do not guess its null argument
            // order: accepting it would make a vendor mismatch look successful.
            Method create = Class.forName("android.hardware.display.IDisplayManager").getMethod(
                    "createVirtualDisplay", configType, callbackType, projectionType, String.class);
            if (create.getReturnType() != Integer.TYPE) {
                throw new UnsupportedOperationException(
                        "DHD native display service returned an unsupported createVirtualDisplay signature.");
            }
            Object result = create.invoke(service, config, callback, null, SHELL_PACKAGE);
            displayId = ((Integer) result).intValue();
            if (displayId <= 0) throw new IOException("Android rejected the task virtual display.");
            // Android otherwise routes IME windows to the default display. The
            // local policy is part of the task-display contract; fail creation
            // if this privileged shell-side call is unavailable.
            setDisplayImePolicyMethod.invoke(windowService, displayId, 0 /* DISPLAY_IME_POLICY_LOCAL */);
            return displayId;
        }

        void releaseVirtualDisplay() {
            if (displayId <= 0) return;
            try { releaseMethod.invoke(service, callback); } catch (Throwable ignored) {}
            displayId = -1;
        }

        private Method findMethod(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
            for (Method method : service.getClass().getMethods()) {
                if (!name.equals(method.getName())) continue;
                if (Arrays.equals(method.getParameterTypes(), parameterTypes)) return method;
            }
            throw new NoSuchMethodException(name);
        }

        private static int displayFlags() {
            // PUBLIC + OWN_CONTENT_ONLY + SUPPORTS_TOUCH + ROTATES_WITH_CONTENT +
            // DESTROY_CONTENT_ON_REMOVAL + TRUSTED + OWN_FOCUS +
            // STEAL_TOP_FOCUS_DISABLED. OWN_FOCUS is ignored by Android unless
            // TRUSTED is present, and without it IME focus falls back to display 0.
            return (1 << 0) | (1 << 3) | (1 << 6) | (1 << 7) | (1 << 8) |
                    (1 << 10) | (1 << 14) | (1 << 16);
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value));
        return result.toString();
    }
}
