package com.phonecontrol.assistant.developer;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Long-lived DHD process started by app_process as the ADB shell user.
 *
 * The process deliberately has no Android application lifecycle. It survives
 * the ADB bootstrap connection and exposes only DHD's typed phone commands on
 * loopback. Wireless Debugging is therefore needed to bootstrap/restart this
 * process, not for every individual phone action.
 */
public final class DhdMaintenanceDaemon {
    private static final String LOOPBACK = "127.0.0.1";
    private static final int SOCKET_TIMEOUT_MS = 20_000;
    private static final long COMMAND_TIMEOUT_MS = 15_000L;
    /** Bumped when the long-lived daemon gains display-session reconciliation. */
    static final String CAPABILITIES =
            "DHD-MAINTENANCE/8 display-lifecycle=1 live-avc=1 display-capture=1 " +
                    "display-density-override=1 display-reconciliation=1";
    private static final Set<String> ALLOWED_EXECUTABLES = new HashSet<>(Arrays.asList(
            "am",
            "dumpsys",
            "input",
            "screencap",
            "true"
    ));

    private DhdMaintenanceDaemon() {}

    public static void main(String[] args) {
        int port = parseIntOption(args, "--port");
        String token = parseStringOption(args, "--token");
        if (port < 1024 || port > 65535 || token == null || token.isEmpty()) {
            return;
        }

        DhdNativeDisplayService displayService = new DhdNativeDisplayService();
        Runtime.getRuntime().addShutdownHook(new Thread(displayService::close, "dhd-display-shutdown"));
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new java.net.InetSocketAddress(InetAddress.getByName(LOOPBACK), port));
            while (true) {
                try (Socket client = server.accept()) {
                    client.setSoTimeout(SOCKET_TIMEOUT_MS);
                    handleClient(client, token, displayService);
                } catch (Throwable ignored) {
                    // A malformed or disconnected client must not kill the
                    // shell-UID daemon. The next request can still connect.
                }
            }
        } catch (Throwable ignored) {
            // Startup failure is observed by the app's health check. Avoid
            // writing to the ADB session after the bootstrap command returns.
        } finally {
            displayService.close();
        }
    }

    private static void handleClient(
            Socket client,
            String expectedToken,
            DhdNativeDisplayService displayService
    ) throws IOException {
        DataInputStream input = new DataInputStream(client.getInputStream());
        DataOutputStream output = new DataOutputStream(client.getOutputStream());
        DhdMaintenanceProtocol.Request request = DhdMaintenanceProtocol.readRequest(input);
        if (!DhdMaintenanceProtocol.tokensEqual(expectedToken, request.token)) {
            DhdMaintenanceProtocol.writeResponse(
                    output,
                    DhdMaintenanceProtocol.EXIT_CODE_UNAVAILABLE,
                    false,
                    new byte[0],
                    "DHD maintenance authentication failed.".getBytes(StandardCharsets.UTF_8)
            );
            output.flush();
            return;
        }

        CommandResult result = execute(request.command, request.binaryOutput, displayService);
        DhdMaintenanceProtocol.writeResponse(
                output,
                result.exitCode,
                result.timedOut,
                result.stdout,
                result.stderr.getBytes(StandardCharsets.UTF_8)
        );
        output.flush();
    }

    private static CommandResult execute(
            List<String> command,
            boolean binaryOutput,
            DhdNativeDisplayService displayService
    ) {
        if (command == null || command.isEmpty()) {
            return CommandResult.failure("DHD maintenance rejected an empty command.");
        }
        String executable = command.get(0);
        if ("dhd-capabilities".equals(executable)) {
            return new CommandResult(0, false, CAPABILITIES.getBytes(StandardCharsets.UTF_8), "");
        }
        if (DhdNativeDisplayService.COMMAND.equals(executable)) {
            DhdNativeDisplayService.CommandResult result = displayService.execute(command, binaryOutput);
            return new CommandResult(result.exitCode, result.timedOut, result.stdout, result.stderr);
        }
        if (!ALLOWED_EXECUTABLES.contains(executable)) {
            return CommandResult.failure("DHD maintenance rejected executable: " + executable);
        }

        List<String> normalized = new ArrayList<>(command);
        normalized.set(0, "/system/bin/" + executable);
        try {
            Process process = new ProcessBuilder(normalized)
                    .redirectErrorStream(false)
                    .start();
            Collector stdout = new Collector(process.getInputStream());
            Collector stderr = new Collector(process.getErrorStream());
            Thread stdoutThread = new Thread(stdout, "dhd-maintenance-stdout");
            Thread stderrThread = new Thread(stderr, "dhd-maintenance-stderr");
            stdoutThread.start();
            stderrThread.start();

            boolean finished = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            stdoutThread.join(1_000L);
            stderrThread.join(1_000L);

            if (!finished) {
                return new CommandResult(
                        DhdMaintenanceProtocol.EXIT_CODE_UNAVAILABLE,
                        true,
                        stdout.bytes(),
                        "DHD maintenance command timed out."
                );
            }
            if (stdout.overflowed || stderr.overflowed) {
                return new CommandResult(
                        DhdMaintenanceProtocol.EXIT_CODE_UNAVAILABLE,
                        false,
                        stdout.bytes(),
                        "DHD maintenance command output was too large."
                );
            }
            return new CommandResult(process.exitValue(), false, stdout.bytes(), stderr.text());
        } catch (Throwable error) {
            String message = error.getMessage();
            return CommandResult.failure(
                    "DHD maintenance could not execute the command: " +
                            (message == null || message.isEmpty() ? error.getClass().getSimpleName() : message)
            );
        }
    }

    private static int parseIntOption(String[] args, String name) {
        String value = parseStringOption(args, name);
        if (value == null) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String parseStringOption(String[] args, String name) {
        String prefix = name + "=";
        for (String arg : args) {
            if (arg != null && arg.startsWith(prefix)) return arg.substring(prefix.length());
        }
        return null;
    }

    private static final class Collector implements Runnable {
        private final java.io.InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private volatile boolean overflowed;

        Collector(java.io.InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (output.size() + read > DhdMaintenanceProtocol.MAX_OUTPUT_BYTES) {
                        overflowed = true;
                        // Keep draining so the child cannot block on a full
                        // pipe before its command timeout is reached.
                        continue;
                    }
                    output.write(buffer, 0, read);
                }
            } catch (IOException ignored) {
                // The process may close its stream while the timeout handler
                // is terminating it; the captured bytes remain useful.
            }
        }

        byte[] bytes() {
            return output.toByteArray();
        }

        String text() {
            return new String(bytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static final class CommandResult {
        final int exitCode;
        final boolean timedOut;
        final byte[] stdout;
        final String stderr;

        CommandResult(int exitCode, boolean timedOut, byte[] stdout, String stderr) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        static CommandResult failure(String message) {
            return new CommandResult(
                    DhdMaintenanceProtocol.EXIT_CODE_UNAVAILABLE,
                    false,
                    new byte[0],
                    message
            );
        }
    }
}
