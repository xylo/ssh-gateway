package de.sshgateway.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Random token sent by the bridge (stdio process) to the UI when establishing a connection,
 * preventing other local processes on the same machine from snooping.
 */
public final class BridgeToken {
    private BridgeToken() {}

    public static String generate() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static void write(Path file, String token) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, token, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows: no POSIX. The file is located in the user profile (restricted to the user by NTFS defaults).
        }
    }

    public static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).strip();
    }

    /** Reads exactly one line raw (byte by byte, unbuffered) so that no subsequent protocol bytes are lost. */
    public static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
        }
        if (c == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.UTF_8);
    }

    public static void writeLine(OutputStream out, String token) throws IOException {
        out.write((token + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Constant-time comparison (low risk locally, but cleaner). */
    public static boolean matches(String received, String expected) {
        if (received.length() != expected.length()) return false;
        int diff = 0;
        for (int i = 0; i < received.length(); i++) diff |= received.charAt(i) ^ expected.charAt(i);
        return diff == 0;
    }
}
