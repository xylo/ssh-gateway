package de.sshgateway.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MultiServerGatewayTest {
	private static final ObjectMapper MAPPER = new ObjectMapper();

	static class MockSshConnection extends SshConnection {
		final List<String> commands = new ArrayList<>();
		final String serverName;

		MockSshConnection(ServerConfig cfg) {
			super(cfg);
			this.serverName = cfg.name();
		}

		@Override
		public ExecResult exec(String command, int timeoutSeconds, byte[] stdin) {
			commands.add(command);
			return new ExecResult(0, "output from " + serverName + ": " + command, "", false, false, false);
		}

		@Override
		public byte[] readFile(String path, long maxBytes) {
			return ("content on " + serverName + " of " + path).getBytes();
		}
	}

	static class MockReviewService implements ReviewService {
		final List<CommandRequest> commandRequests = new ArrayList<>();

		@Override
		public CommandDecision approveCommand(CommandRequest request) {
			commandRequests.add(request);
			return new CommandDecision(CommandDecision.Action.EXECUTE, null);
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
			return "pass";
		}
	}

	private MockSshConnection ssh1;
	private MockSshConnection ssh2;
	private MockReviewService review;
	private GatewayTools tools;
	private File srv1Dir;
	private File srv2Dir;

	@BeforeEach
	void setUp() throws IOException {
		File tempDir = Files.createTempDirectory("sshgw-multitest").toFile();
		tempDir.deleteOnExit();

		srv1Dir = new File(tempDir, "web");
		srv2Dir = new File(tempDir, "db");
		srv1Dir.mkdirs();
		srv2Dir.mkdirs();

		ServerConfig cfg1 = new ServerConfig("web", "web.example.org", 22, "deploy", null, null, List.of(), List.of(), List.of(), 30, 100_000, 100_000);
		ServerConfig cfg2 = new ServerConfig("db", "db.example.org", 22, "postgres", null, null, List.of(), List.of(), List.of(), 30, 100_000, 100_000);

		ssh1 = new MockSshConnection(cfg1);
		ssh2 = new MockSshConnection(cfg2);

		review = new MockReviewService();

		ServerContext ctx1 = new ServerContext("web", cfg1, ssh1,
				new RedactionStore(new File(srv1Dir, "redactions.json").toPath()),
				new PathPolicy(cfg1.denyPaths()),
				new RiskAnalyzer(cfg1.extraRiskPatterns(), List.of()),
				new Backups(new File(srv1Dir, "backups").toPath()),
				new AuditLog(new File(srv1Dir, "audit.log").toPath()),
				List.of());

		ServerContext ctx2 = new ServerContext("db", cfg2, ssh2,
				new RedactionStore(new File(srv2Dir, "redactions.json").toPath()),
				new PathPolicy(cfg2.denyPaths()),
				new RiskAnalyzer(cfg2.extraRiskPatterns(), List.of()),
				new Backups(new File(srv2Dir, "backups").toPath()),
				new AuditLog(new File(srv2Dir, "audit.log").toPath()),
				List.of());

		Map<String, ServerContext> map = new LinkedHashMap<>();
		map.put("web", ctx1);
		map.put("db", ctx2);

		tools = new GatewayTools(map, "web", review);
	}

	private ToolResult callTool(String name, ObjectNode args) throws Exception {
		return tools.definitions().stream()
				.filter(t -> t.name().equals(name))
				.findFirst()
				.orElseThrow()
				.handler()
				.call(args);
	}

	@Test
	void testListServersTool() throws Exception {
		ToolResult res = callTool("list_servers", MAPPER.createObjectNode());
		assertFalse(res.isError());
		assertTrue(res.text().contains("web: deploy@web.example.org:22 [default]"));
		assertTrue(res.text().contains("db: postgres@db.example.org:22"));
	}

	@Test
	void testRunCommandOnDefaultServer() throws Exception {
		ObjectNode args = MAPPER.createObjectNode();
		args.put("command", "uname -a");

		ToolResult res = callTool("run_command", args);
		assertFalse(res.isError());
		assertEquals(1, ssh1.commands.size());
		assertEquals("uname -a", ssh1.commands.get(0));
		assertEquals(0, ssh2.commands.size());
		assertEquals("web", review.commandRequests.get(0).server());
	}

	@Test
	void testRunCommandOnSpecificServer() throws Exception {
		ObjectNode args = MAPPER.createObjectNode();
		args.put("server", "db");
		args.put("command", "psql -c 'SELECT 1'");

		ToolResult res = callTool("run_command", args);
		assertFalse(res.isError());
		assertEquals(0, ssh1.commands.size());
		assertEquals(1, ssh2.commands.size());
		assertEquals("psql -c 'SELECT 1'", ssh2.commands.get(0));
		assertEquals("db", review.commandRequests.get(0).server());

		// Verify audit log exists in db directory
		File auditFile = new File(srv2Dir, "audit.log");
		assertTrue(auditFile.exists());
		String auditContent = Files.readString(auditFile.toPath());
		assertTrue(auditContent.contains("COMMAND-EXECUTED"));
	}

	@Test
	void testRunCommandUnknownServer() throws Exception {
		ObjectNode args = MAPPER.createObjectNode();
		args.put("server", "unknown-srv");
		args.put("command", "ls");

		ToolResult res = callTool("run_command", args);
		assertTrue(res.isError());
		assertTrue(res.text().contains("Unknown server 'unknown-srv'"));
		assertTrue(res.text().contains("Available servers: web, db"));
	}

	@Test
	void testReadFileOnSpecificServer() throws Exception {
		ObjectNode args = MAPPER.createObjectNode();
		args.put("server", "db");
		args.put("path", "/etc/postgresql/postgresql.conf");

		ToolResult res = callTool("read_file", args);
		assertFalse(res.isError());
		assertTrue(res.text().contains("content on db of /etc/postgresql/postgresql.conf"));
	}

	@Test
	void testSshConnectionUnknownHostErrorMessage() {
		ServerConfig invalidCfg = new ServerConfig("badserver", "this-host-does-not-exist-12345.invalid", 22, "user", null, null, List.of(), List.of(), List.of(), 5, 100_000, 100_000);
		SshConnection conn = new SshConnection(invalidCfg);
		IOException ex = assertThrows(IOException.class, () -> conn.exec("ls", 5));
		assertTrue(ex.getMessage().contains("Cannot resolve host 'this-host-does-not-exist-12345.invalid' for server 'badserver'"));
		assertTrue(ex.getMessage().contains("Please check hostname in config.json"));
	}
}
