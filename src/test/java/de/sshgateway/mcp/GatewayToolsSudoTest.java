package de.sshgateway.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.sshgateway.config.GatewayConfig;
import de.sshgateway.config.ServerConfig;
import de.sshgateway.redaction.RedactionStore;
import de.sshgateway.review.ReviewService;
import de.sshgateway.security.AuditLog;
import de.sshgateway.security.Backups;
import de.sshgateway.security.PathPolicy;
import de.sshgateway.security.RiskAnalyzer;
import de.sshgateway.server.ServerContext;
import de.sshgateway.ssh.ExecResult;
import de.sshgateway.ssh.SshConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GatewayToolsSudoTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ServerConfig serverCfg;
	private GatewayConfig cfg;
	private TestSshConnection ssh;
	private TestReviewService review;
	private GatewayTools tools;

	static class TestSshConnection extends SshConnection {
		record ExecCall(String command, int timeout, byte[] stdin) {}
		final List<ExecCall> calls = new ArrayList<>();
		int sudoValidateExitCode = 1; // Default: sudo -n -v fails (not unlocked)
		int sudoAuthExitCode = 0;     // Default: sudo -S -p '' -v succeeds

		TestSshConnection(ServerConfig cfg) {
			super(cfg);
		}

		@Override
		public ExecResult exec(String command, int timeoutSeconds, byte[] stdin) {
			calls.add(new ExecCall(command, timeoutSeconds, stdin));
			if ("sudo -n -v".equals(command)) {
				return new ExecResult(sudoValidateExitCode, "", sudoValidateExitCode == 0 ? "" : "sudo: a password is required", false, false, false);
			}
			if ("sudo -S -p '' -v".equals(command)) {
				return new ExecResult(sudoAuthExitCode, "", sudoAuthExitCode == 0 ? "" : "sudo: 1 incorrect password attempt", false, false, false);
			}
			return new ExecResult(0, "success output for: " + command, "", false, false, false);
		}
	}

	static class TestReviewService implements ReviewService {
		CommandDecision commandDecision = new CommandDecision(CommandDecision.Action.EXECUTE, null);
		final List<String> passwordPrompts = new ArrayList<>();
		final List<String> passwordErrors = new ArrayList<>();
		String nextPassword = "secret-password";
		final AtomicInteger askPasswordCalls = new AtomicInteger();

		@Override
		public CommandDecision approveCommand(CommandRequest request) {
			return commandDecision;
		}

		@Override
		public OutputDecision reviewOutput(OutputRequest request) {
			return new OutputDecision(true, request.initialText(), null);
		}

		@Override
		public EditDecision approveEdit(EditRequest request) {
			return new EditDecision(true, null);
		}

		@Override
		public String askUser(String question) {
			return "user answer";
		}

		@Override
		public String askPassword(String server, String prompt, String errorMessage) {
			askPasswordCalls.incrementAndGet();
			passwordPrompts.add(prompt);
			passwordErrors.add(errorMessage);
			return nextPassword;
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		File tempDir = Files.createTempDirectory("sshgw-test").toFile();
		tempDir.deleteOnExit();
		File auditFile = new File(tempDir, "audit.log");
		File redactionFile = new File(tempDir, "redactions.json");
		File backupsDir = new File(tempDir, "backups");

		serverCfg = new ServerConfig(
				"testserver", "test.example.com", 22, "testuser", null, null,
				List.of(), List.of(), List.of(),
				30, 100_000, 100_000
		);

		cfg = new GatewayConfig(
				Map.of("testserver", serverCfg), "testserver", "idea",
				30, 100_000, 100_000, 51823,
				List.of(), List.of(), List.of()
		);

		ssh = new TestSshConnection(serverCfg);
		review = new TestReviewService();
		AuditLog audit = new AuditLog(auditFile.toPath());
		RedactionStore store = new RedactionStore(redactionFile.toPath());
		PathPolicy policy = new PathPolicy(serverCfg.denyPaths());
		RiskAnalyzer risk = new RiskAnalyzer(serverCfg.extraRiskPatterns(), policy.literalHints());
		Backups backups = new Backups(backupsDir.toPath());

		ServerContext ctx = new ServerContext("testserver", serverCfg, ssh, store, policy, risk, backups, audit, List.of());
		tools = new GatewayTools(Map.of("testserver", ctx), "testserver", review);
	}

	private ToolResult callRunCommand(GatewayTools gTools, String command) throws Exception {
		ObjectNode args = MAPPER.createObjectNode();
		args.put("command", command);
		return gTools.definitions().stream()
				.filter(t -> t.name().equals("run_command"))
				.findFirst()
				.orElseThrow()
				.handler()
				.call(args);
	}

	@Test
	void testNonSudoCommandDoesNotCheckSudo() throws Exception {
		ToolResult res = callRunCommand(tools, "ls -la /var/log");
		assertFalse(res.isError());
		assertEquals(1, ssh.calls.size());
		assertEquals("ls -la /var/log", ssh.calls.getFirst().command());
		assertEquals(0, review.askPasswordCalls.get());
	}

	@Test
	void testSudoCommandAlreadyUnlocked() throws Exception {
		ssh.sudoValidateExitCode = 0; // Already unlocked

		ToolResult res = callRunCommand(tools, "sudo systemctl status nginx");
		assertFalse(res.isError());
		assertEquals(2, ssh.calls.size());
		assertEquals("sudo -n -v", ssh.calls.get(0).command());
		assertEquals("sudo systemctl status nginx", ssh.calls.get(1).command());
		assertEquals(0, review.askPasswordCalls.get());
	}

	@Test
	void testSudoCommandNeedsPasswordAndSucceeds() throws Exception {
		ssh.sudoValidateExitCode = 1; // Needs password
		ssh.sudoAuthExitCode = 0;     // Auth succeeds
		review.nextPassword = "correct-password";

		ToolResult res = callRunCommand(tools, "sudo apt update");
		assertFalse(res.isError());
		assertEquals(3, ssh.calls.size());
		assertEquals("sudo -n -v", ssh.calls.get(0).command());
		assertEquals("sudo -S -p '' -v", ssh.calls.get(1).command());
		assertArrayEquals("correct-password\n".getBytes(StandardCharsets.UTF_8), ssh.calls.get(1).stdin());
		assertEquals("sudo apt update", ssh.calls.get(2).command());
		assertEquals(1, review.askPasswordCalls.get());
		assertNull(review.passwordErrors.getFirst());
	}

	@Test
	void testSudoCommandRetryAfterWrongPassword() throws Exception {
		ssh.sudoValidateExitCode = 1;
		review.askPasswordCalls.set(0);

		// First password attempt fails, second succeeds
		TestSshConnection retrySsh = new TestSshConnection(serverCfg) {
			int authCount = 0;
			@Override
			public ExecResult exec(String command, int timeoutSeconds, byte[] stdin) {
				calls.add(new ExecCall(command, timeoutSeconds, stdin));
				if ("sudo -n -v".equals(command)) {
					return new ExecResult(1, "", "password required", false, false, false);
				}
				if ("sudo -S -p '' -v".equals(command)) {
					authCount++;
					int exit = (authCount == 1) ? 1 : 0;
					return new ExecResult(exit, "", exit == 0 ? "" : "incorrect password", false, false, false);
				}
				return new ExecResult(0, "success", "", false, false, false);
			}
		};

		File tempDir = Files.createTempDirectory("sshgw-test2").toFile();
		tempDir.deleteOnExit();
		AuditLog audit = new AuditLog(new File(tempDir, "audit.log").toPath());
		RedactionStore store = new RedactionStore(new File(tempDir, "redactions.json").toPath());
		PathPolicy policy = new PathPolicy(serverCfg.denyPaths());
		RiskAnalyzer risk = new RiskAnalyzer(serverCfg.extraRiskPatterns(), policy.literalHints());
		Backups backups = new Backups(new File(tempDir, "backups").toPath());
		ServerContext retryCtx = new ServerContext("testserver", serverCfg, retrySsh, store, policy, risk, backups, audit, List.of());
		GatewayTools toolsWithRetry = new GatewayTools(Map.of("testserver", retryCtx), "testserver", review);

		ToolResult res = callRunCommand(toolsWithRetry, "sudo reboot");

		assertFalse(res.isError());
		assertEquals(2, review.askPasswordCalls.get());
		assertNull(review.passwordErrors.get(0));
		assertNotNull(review.passwordErrors.get(1));
		assertTrue(review.passwordErrors.get(1).contains("Authentication failed"));
	}

	@Test
	void testSudoCommandCancelledByUser() throws Exception {
		ssh.sudoValidateExitCode = 1;
		review.nextPassword = null; // User clicked Cancel

		ToolResult res = callRunCommand(tools, "sudo rm -rf /tmp/test");
		assertTrue(res.isError());
		assertTrue(res.text().contains("cancelled"));
		assertEquals(1, ssh.calls.size());
		assertEquals("sudo -n -v", ssh.calls.getFirst().command());
		assertEquals(1, review.askPasswordCalls.get());
	}

	@Test
	void testSudoCommandRejectedInApprovalDialog() throws Exception {
		review.commandDecision = new ReviewService.CommandDecision(ReviewService.CommandDecision.Action.REJECT, "Not allowed by policy");

		ToolResult res = callRunCommand(tools, "sudo touch /root/test");
		assertTrue(res.isError());
		assertTrue(res.text().contains("rejected"));
		assertEquals(0, ssh.calls.size());
		assertEquals(0, review.askPasswordCalls.get());
	}
}
