package de.sshgateway.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;

/** Simple log of all actions and decisions (without output contents). */
public final class AuditLog {
    private final Path file;

    public AuditLog(Path file) { this.file = file; }

    public synchronized void log(String event, String detail) {
        String line = OffsetDateTime.now() + "\t" + event + "\t" + detail.replace("\r", "\\r").replace("\n", "\\n") + "\n";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Audit log not writable: " + e);
        }
    }
}
