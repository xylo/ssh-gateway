package de.sshgateway.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Configuration for a single remote server.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ServerConfig(
		String name,
		String host,
		int port,
		String user,
		String keyFile,
		String knownHostsFile,
		List<String> denyPaths,
		List<String> extraRiskPatterns,
		List<String> blockedCommandPatterns,
		int defaultTimeoutSeconds,
		long maxOutputBytes,
		long maxFileBytes) {

	public ServerConfig {
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("Server configuration: 'host' is missing");
		}
		if (user == null || user.isBlank()) {
			throw new IllegalArgumentException("Server configuration: 'user' is missing");
		}
		if (port <= 0) {
			port = 22;
		}
		if (defaultTimeoutSeconds <= 0) {
			defaultTimeoutSeconds = 120;
		}
		if (maxOutputBytes <= 0) {
			maxOutputBytes = 1_000_000;
		}
		if (maxFileBytes <= 0) {
			maxFileBytes = 512 * 1024;
		}
		denyPaths = denyPaths == null ? GatewayConfig.DEFAULT_DENY_PATHS : List.copyOf(denyPaths);
		extraRiskPatterns = extraRiskPatterns == null ? List.of() : List.copyOf(extraRiskPatterns);
		blockedCommandPatterns = blockedCommandPatterns == null ? List.of() : List.copyOf(blockedCommandPatterns);
	}

	public String displayName() {
		return (name != null && !name.isBlank() ? name : host) + " (" + user + "@" + host + (port != 22 ? ":" + port : "") + ")";
	}

	public ServerConfig withName(String newName) {
		return new ServerConfig(newName, host, port, user, keyFile, knownHostsFile,
				denyPaths, extraRiskPatterns, blockedCommandPatterns, defaultTimeoutSeconds, maxOutputBytes, maxFileBytes);
	}
}
