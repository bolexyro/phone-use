package com.phonecontrol.assistant.developer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Private, length-delimited protocol between the app process and the DHD
 * shell-UID maintenance process.
 *
 * This intentionally is not a general shell API. The app sends only the
 * typed argv assembled by DHD's phone-control implementation, and the daemon
 * applies its own executable allowlist before starting a process.
 */
final class DhdMaintenanceProtocol {
    static final int MAGIC = 0x44484431; // DHD1
    static final int VERSION = 1;
    static final int EXIT_CODE_UNAVAILABLE = -1;
    static final int MAX_ARGUMENTS = 32;
    static final int MAX_ARGUMENT_BYTES = 4096;
    static final int MAX_TOKEN_BYTES = 128;
    static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;

    private DhdMaintenanceProtocol() {}

    static void writeRequest(
            DataOutputStream output,
            String token,
            List<String> command,
            boolean binaryOutput
    ) throws IOException {
        writeHeader(output);
        writeString(output, token, MAX_TOKEN_BYTES);
        if (command == null || command.isEmpty() || command.size() > MAX_ARGUMENTS) {
            throw new IOException("Invalid maintenance command length.");
        }
        output.writeInt(command.size());
        for (String argument : command) {
            writeString(output, argument, MAX_ARGUMENT_BYTES);
        }
        output.writeBoolean(binaryOutput);
    }

    static Request readRequest(DataInputStream input) throws IOException {
        readHeader(input);
        String token = readString(input, MAX_TOKEN_BYTES);
        int argumentCount = input.readInt();
        if (argumentCount <= 0 || argumentCount > MAX_ARGUMENTS) {
            throw new IOException("Invalid maintenance command length.");
        }
        List<String> command = new ArrayList<>(argumentCount);
        for (int index = 0; index < argumentCount; index++) {
            command.add(readString(input, MAX_ARGUMENT_BYTES));
        }
        return new Request(token, command, input.readBoolean());
    }

    static void writeResponse(
            DataOutputStream output,
            int exitCode,
            boolean timedOut,
            byte[] stdout,
            byte[] stderr
    ) throws IOException {
        writeHeader(output);
        output.writeInt(exitCode);
        output.writeBoolean(timedOut);
        writeBytes(output, stdout);
        writeBytes(output, stderr);
    }

    static Response readResponse(DataInputStream input) throws IOException {
        readHeader(input);
        int exitCode = input.readInt();
        boolean timedOut = input.readBoolean();
        byte[] stdout = readBytes(input);
        byte[] stderr = readBytes(input);
        return new Response(exitCode, timedOut, stdout, stderr);
    }

    static boolean tokensEqual(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void writeHeader(DataOutputStream output) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
    }

    private static void readHeader(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC) throw new IOException("Invalid maintenance protocol magic.");
        if (input.readInt() != VERSION) throw new IOException("Unsupported maintenance protocol version.");
    }

    private static void writeString(DataOutputStream output, String value, int maxBytes) throws IOException {
        if (value == null) throw new IOException("Maintenance protocol values must not be null.");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IOException("Maintenance protocol value is too long.");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maxBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maxBytes) throw new IOException("Maintenance protocol value is too long.");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream output, byte[] bytes) throws IOException {
        byte[] value = bytes == null ? new byte[0] : bytes;
        if (value.length > MAX_OUTPUT_BYTES) throw new IOException("Maintenance command output is too large.");
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_OUTPUT_BYTES) throw new IOException("Maintenance command output is too large.");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    static final class Request {
        final String token;
        final List<String> command;
        final boolean binaryOutput;

        Request(String token, List<String> command, boolean binaryOutput) {
            this.token = token;
            this.command = command;
            this.binaryOutput = binaryOutput;
        }
    }

    static final class Response {
        final int exitCode;
        final boolean timedOut;
        final byte[] stdout;
        final byte[] stderr;

        Response(int exitCode, boolean timedOut, byte[] stdout, byte[] stderr) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}
