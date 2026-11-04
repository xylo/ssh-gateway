package de.sshgateway.server;

import de.sshgateway.config.ServerConfig;
import de.sshgateway.redaction.RedactionStore;
import de.sshgateway.security.AuditLog;
import de.sshgateway.security.Backups;
import de.sshgateway.security.PathPolicy;
import de.sshgateway.security.RiskAnalyzer;
import de.sshgateway.ssh.SshConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Encapsulates the per-server runtime state: SSH connection, server-specific directories
 * (~/.ssh-gateway/SERVERNAME/{backups, audit.log, redactions.json}), security policies and risk analyzer.
 */
public final class ServerContext implements AutoCloseable {
	private final String name;
	private final ServerConfig config;
	private final SshConnection ssh;
	private final RedactionStore redactions;
	private final PathPolicy policy;
	private final RiskAnalyzer risk;
	private final Backups backups;
	private final AuditLog audit;
	private final List<Pattern> blockedPatterns;

	public ServerContext(String name, ServerConfig config, SshConnection ssh, RedactionStore redactions,
						 PathPolicy policy, RiskAnalyzer risk, Backups backups, AuditLog audit,
						 List<Pattern> blockedPatterns) {
		this.name = name;
		this.config = config;
		this.ssh = ssh;
		this.redactions = redactions;
		this.policy = policy;
		this.risk = risk;
		this.backups = backups;
		this.audit = audit;
		this.blockedPatterns = blockedPatterns == null ? List.of() : List.copyOf(blockedPatterns);
	}

	public static ServerContext create(Path home, ServerConfig config) throws IOException {
		String serverName = config.name();
		Path serverDir = home.resolve(serverName);
		Files.createDirectories(serverDir);

		RedactionStore redactions = new RedactionStore(serverDir.resolve("redactions.json"));
		AuditLog audit = new AuditLog(serverDir.resolve("audit.log"));
		Backups backups = new Backups(serverDir.resolve("backups"));
		PathPolicy policy = new PathPolicy(config.denyPaths());
		RiskAnalyzer risk = new RiskAnalyzer(config.extraRiskPatterns(), policy.literalHints());
		SshConnection ssh = new SshConnection(config);

		List<Pattern> blockedPatterns = new ArrayList<>();
		for (String p : config.blockedCommandPatterns()) {
			blockedPatterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
		}

		return new ServerContext(serverName, config, ssh, redactions, policy, risk, backups, audit, blockedPatterns);
	}

	public String name() {
		return name;
	}

	public ServerConfig config() {
		return config;
	}

	public SshConnection ssh() {
		return ssh;
	}

	public RedactionStore redactions() {
		return redactions;
	}

	public PathPolicy policy() {
		return policy;
	}

	public RiskAnalyzer risk() {
		return risk;
	}

	public Backups backups() {
		return backups;
	}

	public AuditLog audit() {
		return audit;
	}

	public List<Pattern> blockedPatterns() {
		return blockedPatterns;
	}

	@Override
	public void close() {
		try {
			ssh.close();
		} catch (Exception ignored) {
		}
	}
}
