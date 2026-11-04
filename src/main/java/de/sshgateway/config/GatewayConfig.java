package de.sshgateway.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration from ~/.ssh-gateway/config.json (or $SSHGW_HOME/config.json).
 * Passwords are intentionally NOT stored in the file: use SSHGW_PASSWORD (or SSHGW_PASSWORD_<SERVER>)
 * and SSHGW_KEY_PASSPHRASE (or SSHGW_KEY_PASSPHRASE_<SERVER>) as environment variables.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayConfig(
		Map<String, ServerConfig> servers,
		String defaultServer,
		String ideaCommand,
		int defaultTimeoutSeconds,
		long maxOutputBytes,
		long maxFileBytes,
		int bridgePort,
		List<String> denyPaths,
		List<String> extraRiskPatterns,
		List<String> blockedCommandPatterns) {

	public static final List<String> DEFAULT_DENY_PATHS = List.of(
			"**/.env", "**/.env.*", "**/*.pem", "**/*.key", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.kdbx",
			"**/id_rsa*", "**/id_ed25519*", "**/id_ecdsa*", "**/id_dsa*",
			"**/.ssh/**", "**/.gnupg/**", "**/.aws/**",
			"**/.git-credentials", "**/.netrc", "**/.pgpass",
			"/etc/shadow", "/etc/gshadow", "/etc/sudoers", "/etc/sudoers.d/**", "/proc/*/environ");

	public GatewayConfig {
		if (servers == null || servers.isEmpty()) {
			throw new IllegalArgumentException("config.json: No servers configured.");
		}
		Map<String, ServerConfig> namedServers = new LinkedHashMap<>();
		for (var entry : servers.entrySet()) {
			String name = entry.getKey();
			ServerConfig sc = entry.getValue();
			if (sc != null && (sc.name() == null || !sc.name().equals(name))) {
				sc = sc.withName(name);
			}
			namedServers.put(name, sc);
		}
		servers = Collections.unmodifiableMap(namedServers);
		if (defaultServer != null && !servers.containsKey(defaultServer)) {
			throw new IllegalArgumentException("config.json: defaultServer '" + defaultServer + "' not found in configured servers.");
		}
		if (defaultServer == null && servers.size() == 1) {
			defaultServer = servers.keySet().iterator().next();
		}
		if (ideaCommand == null) ideaCommand = "idea";
		if (defaultTimeoutSeconds <= 0) defaultTimeoutSeconds = 120;
		if (maxOutputBytes <= 0) maxOutputBytes = 1_000_000;
		if (maxFileBytes <= 0) maxFileBytes = 512 * 1024;
		if (bridgePort <= 0) bridgePort = 51823;
		denyPaths = denyPaths == null ? DEFAULT_DENY_PATHS : List.copyOf(denyPaths);
		extraRiskPatterns = extraRiskPatterns == null ? List.of() : List.copyOf(extraRiskPatterns);
		blockedCommandPatterns = blockedCommandPatterns == null ? List.of() : List.copyOf(blockedCommandPatterns);
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
