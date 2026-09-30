package de.sshgateway.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Local backup of the original file before every change (~/.ssh-gateway/backups).
 */
public final class Backups {
	private final Path dir;

	public Backups(Path dir) {
		this.dir = dir;
	}

	public Path save(String remotePath, byte[] content) throws IOException {
		String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").format(LocalDateTime.now());
		String name = remotePath.replaceAll("[^A-Za-z0-9._-]", "_");
		Files.createDirectories(dir);
		Path p = dir.resolve(ts + "__" + name);
		Files.write(p, content);
		return p;
	}
}
