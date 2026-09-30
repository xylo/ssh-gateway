package de.sshgateway.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Configuration from ~/.ssh-gateway/config.json (or $SSHGW_HOME/config.json).
 * Passwords are intentionally NOT stored in the file: use SSHGW_PASSWORD or SSHGW_KEY_PASSPHRASE as environment variables.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayConfig(
		String host,
		int port,
		String user,
		String keyFile,
		String knownHostsFile,
		List<String> denyPaths,
		List<String> extraRiskPatterns,
		List<String> blockedCommandPatterns,
		String ideaCommand,
		int defaultTimeoutSeconds,
		long maxOutputBytes,
		long maxFileBytes,
		int bridgePort) {

	public static final List<String> DEFAULT_DENY_PATHS = List.of(
			"**/.env", "**/.env.*", "**/*.pem", "**/*.key", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.kdbx",
			"**/id_rsa*", "**/id_ed25519*", "**/id_ecdsa*", "**/id_dsa*",
			"**/.ssh/**", "**/.gnupg/**", "**/.aws/**",
			"**/.git-credentials", "**/.netrc", "**/.pgpass",
			"/etc/shadow", "/etc/gshadow", "/etc/sudoers", "/etc/sudoers.d/**", "/proc/*/environ");

	public GatewayConfig {
		if (host == null || host.isBlank()) throw new IllegalArgumentException("config.json: 'host' is missing");
		if (user == null || user.isBlank()) throw new IllegalArgumentException("config.json: 'user' is missing");
		if (port <= 0) port = 22;
		denyPaths = denyPaths == null ? DEFAULT_DENY_PATHS : List.copyOf(denyPaths);
		extraRiskPatterns = extraRiskPatterns == null ? List.of() : List.copyOf(extraRiskPatterns);
		blockedCommandPatterns = blockedCommandPatterns == null ? List.of() : List.copyOf(blockedCommandPatterns);
		if (ideaCommand == null) ideaCommand = "idea";
		if (defaultTimeoutSeconds <= 0) defaultTimeoutSeconds = 120;
		if (maxOutputBytes <= 0) maxOutputBytes = 1_000_000;
		if (maxFileBytes <= 0) maxFileBytes = 512 * 1024;
		if (bridgePort <= 0) bridgePort = 51823;
	}

	public static Path homeDir() {
		String env = System.getenv("SSHGW_HOME");
		if (env != null && !env.isBlank()) return Path.of(env);
		return Path.of(System.getProperty("user.home"), ".ssh-gateway");
	}

	public static GatewayConfig load(Path home) throws IOException {
		Path f = home.resolve("config.json");
		if (!Files.exists(f)) {
			throw new IOException("Configuration file missing: " + f + " (template: config.example.json)");
		}
		return new ObjectMapper().readValue(f.toFile(), GatewayConfig.class);
	}
}
