package de.sshgateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.sshgateway.config.ServerConfig;
import de.sshgateway.redaction.Redactor;
import de.sshgateway.review.ReviewService;
import de.sshgateway.review.ReviewService.CommandDecision;
import de.sshgateway.review.ReviewService.CommandRequest;
import de.sshgateway.review.ReviewService.EditRequest;
import de.sshgateway.review.ReviewService.OutputRequest;
import de.sshgateway.server.ServerContext;
import de.sshgateway.ssh.ExecResult;
import de.sshgateway.ssh.SshConnection;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import lombok.val;

/**
 * The tools that Claude sees. Supports multi-server environments.
 * Every action goes through a blocklist, approval, and output review.
 */
public final class GatewayTools {

	public static final String INSTRUCTIONS = """
			This server is a gateway to remote Linux servers. A human reviews everything you do:
			- Specify the target 'server' parameter when executing commands or accessing files.
			- Every command must be approved by the user; it may be rejected (you will get the reason). Do not retry rejected commands unchanged.
			- Command output and file contents are reviewed by the user first. Sensitive parts are replaced by the placeholder \
			'*** SENSIBLE INFORMATION ***'. Never write this placeholder into files and never guess the hidden values.
			- File changes are shown to the user as a diff and may be rejected.
			- Some paths (secrets, keys, .env files) are blocked entirely.
			- Prefer read-only commands to investigate before changing anything. Use the ask_user tool if requirements are unclear.
			- Relative paths are relative to the SSH user's home directory on the target server.
			""";

	private static final String DENIED_MSG = "Access to this path is blocked by the gateway's deny list.";
	private static final String PLACEHOLDER_MSG = "The text contains the redaction placeholder '" + Redactor.REPLACEMENT
			+ "'. Redacted values cannot be written back. Choose old_string/new_string/content without redacted parts.";
	private static final Pattern SUDO_PATTERN = Pattern.compile("\\bsudo\\b");

	private final Map<String, ServerContext> serverContexts;
	private final String defaultServer;
	private final ReviewService review;

	public GatewayTools(Map<String, ServerContext> serverContexts, String defaultServer, ReviewService review) {
		if (serverContexts == null || serverContexts.isEmpty()) {
			throw new IllegalArgumentException("GatewayTools requires at least one server context.");
		}
		this.serverContexts = Collections.unmodifiableMap(new LinkedHashMap<>(serverContexts));
		this.defaultServer = defaultServer != null && this.serverContexts.containsKey(defaultServer)
				? defaultServer
				: (this.serverContexts.size() == 1 ? this.serverContexts.keySet().iterator().next() : null);
		this.review = review;
	}

	public static String buildInstructions(Map<String, ServerConfig> servers, String defaultServer) {
		StringBuilder sb = new StringBuilder();
		sb.append("This server is a gateway to ").append(servers.size() == 1 ? "one remote Linux server" : servers.size() + " remote Linux servers").append(":\n");
		for (var entry : servers.entrySet()) {
			var s = entry.getValue();
			sb.append("- ").append(entry.getKey()).append(": ").append(s.user()).append("@").append(s.host()).append(":").append(s.port());
			if (entry.getKey().equals(defaultServer)) {
				sb.append(" [default]");
			}
			sb.append("\n");
		}
		sb.append("""
				A human reviews everything you do:
				- Specify the target 'server' parameter when running commands or accessing files.
				- Every command must be approved by the user; it may be rejected (you will get the reason). Do not retry rejected commands unchanged.
				- Command output and file contents are reviewed by the user first. Sensitive parts are replaced by the placeholder '*** SENSIBLE INFORMATION ***'. Never write this placeholder into files and never guess the hidden values.
				- File changes are shown to the user as a diff and may be rejected.
				- Some paths (secrets, keys, .env files) are blocked entirely.
				- Prefer read-only commands to investigate before changing anything. Use the ask_user tool if requirements are unclear.
				- Relative paths are relative to the SSH user's home directory on the target server.
				""");
		return sb.toString();
	}

	public List<ToolDef> definitions() {
		String serverListDesc = String.join(", ", serverContexts.keySet());
		boolean serverParamRequired = serverContexts.size() > 1 && defaultServer == null;
		String serverPropDesc = "Target server name (" + serverListDesc + ")"
				+ (defaultServer != null ? ". Default: '" + defaultServer + "'" : "");

		List<ToolDef> defs = new ArrayList<>();
		defs.add(new ToolDef("run_command",
				"Run a shell command on the remote server. The user must approve every command and reviews its output before you see it.",
				schema(
						prop("server", "string", serverPropDesc, serverParamRequired),
						prop("command", "string", "The shell command to run", true),
						prop("description", "string", "One sentence explaining to the user why this command is needed", false),
						prop("timeout_seconds", "integer", "Timeout in seconds (max 3600)", false)),
				this::runCommand));

		defs.add(new ToolDef("read_file",
				"Read a text file from the remote server. The user reviews (and may redact) the content before you receive it.",
				schema(
						prop("server", "string", serverPropDesc, serverParamRequired),
						prop("path", "string", "Absolute path or path relative to the home directory", true)),
				this::readFile));

		defs.add(new ToolDef("edit_file",
				"Replace exact text in a remote file. The user sees a diff and must approve. old_string must match exactly once unless replace_all is true.",
				schema(
						prop("server", "string", serverPropDesc, serverParamRequired),
						prop("path", "string", "File path", true),
						prop("old_string", "string", "Exact text to replace (must not contain redacted placeholders)", true),
						prop("new_string", "string", "Replacement text", true),
						prop("replace_all", "boolean", "Replace all occurrences (default false)", false)),
				this::editFile));

		defs.add(new ToolDef("write_file",
				"Create or completely overwrite a remote text file. The user sees a diff and must approve. Prefer edit_file for small changes.",
				schema(
						prop("server", "string", serverPropDesc, serverParamRequired),
						prop("path", "string", "File path", true),
						prop("content", "string", "Complete new file content", true)),
				this::writeFile));

		defs.add(new ToolDef("list_dir",
				"List a remote directory. The user reviews the listing before you receive it.",
				schema(
						prop("server", "string", serverPropDesc, serverParamRequired),
						prop("path", "string", "Directory path (default: home directory)", false)),
				this::listDir));

		defs.add(new ToolDef("list_servers",
				"List all configured remote servers available through this gateway.",
				schema(),
				this::listServers));

		defs.add(new ToolDef("ask_user",
				"Ask the user a clarifying question in a dialog and wait for the answer.",
				schema(prop("question", "string", "The question", true)),
				this::askUser));

		return List.copyOf(defs);
	}

	private ServerContext resolveServer(JsonNode a) {
		String serverName = a != null && a.hasNonNull("server") ? a.get("server").asText().trim() : null;
		if (serverName != null && !serverName.isEmpty()) {
			ServerContext ctx = serverContexts.get(serverName);
			if (ctx == null) {
				throw new IllegalArgumentException("Unknown server '" + serverName + "'. Available servers: "
						+ String.join(", ", serverContexts.keySet()));
			}
			return ctx;
		}
		if (defaultServer != null && serverContexts.containsKey(defaultServer)) {
			return serverContexts.get(defaultServer);
		}
		if (serverContexts.size() == 1) {
			return serverContexts.values().iterator().next();
		}
		throw new IllegalArgumentException("Parameter 'server' is required because multiple servers are configured. Available servers: "
				+ String.join(", ", serverContexts.keySet()));
	}

	// ---------- list_servers ----------

	private ToolResult listServers(JsonNode a) {
		StringBuilder sb = new StringBuilder("Available servers:\n");
		for (ServerContext ctx : serverContexts.values()) {
			var sc = ctx.config();
			sb.append("- ").append(ctx.name()).append(": ")
					.append(sc.user()).append("@").append(sc.host()).append(":").append(sc.port());
			if (ctx.name().equals(defaultServer)) {
				sb.append(" [default]");
			}
			sb.append("\n");
		}
		return ToolResult.ok(sb.toString().trim());
	}

	// ---------- run_command ----------

	private ToolResult runCommand(JsonNode a) throws Exception {
		ServerContext ctx;
		try {
			ctx = resolveServer(a);
		} catch (IllegalArgumentException e) {
			return ToolResult.error(e.getMessage());
		}

		val command = requireString(a, "command");
		val description = optString(a, "description");
		val timeout = Math.clamp(a.path("timeout_seconds").asInt(ctx.config().defaultTimeoutSeconds()), 1, 3600);

		for (Pattern p : ctx.blockedPatterns()) {
			if (p.matcher(command).find()) {
				ctx.audit().log("COMMAND-BLOCKED", command);
				return ToolResult.error("This command matches a blocking rule of the gateway for server '" + ctx.name() + "' and was not executed.");
			}
		}
		ctx.audit().log("COMMAND-PROPOSED", command);
		val d = review.approveCommand(new CommandRequest(ctx.name(), command, description, ctx.risk().analyze(command)));
		if (d.action() == CommandDecision.Action.REJECT) {
			ctx.audit().log("COMMAND-REJECTED", command);
			return ToolResult.error("The user rejected this command on " + ctx.name() + ". It was NOT executed." + reasonSuffix(d.reason()));
		}

		if (SUDO_PATTERN.matcher(command).find()) {
			if (!ensureSudoValidated(ctx)) {
				ctx.audit().log("SUDO-ABORTED", command);
				return ToolResult.error("Sudo authentication was not successful or was cancelled by the user. The command was NOT executed.");
			}
		}

		ctx.audit().log(d.action() == CommandDecision.Action.EXECUTE_HELD ? "COMMAND-EXECUTED-HELD" : "COMMAND-EXECUTED", command);
		val r = ctx.ssh().exec(command, timeout);
		return deliver(ctx, "Output from: " + shorten(command), formatExec(r, timeout),
				d.action() == CommandDecision.Action.EXECUTE_HELD);
	}

	private boolean ensureSudoValidated(ServerContext ctx) throws Exception {
		val testRes = ctx.ssh().exec("sudo -n -v", 10);
		if (testRes.exitCode() == 0) {
			return true;
		}

		String errorMsg = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			val prompt = "A sudo command is about to be executed on [" + ctx.name() + "] "
					+ ctx.config().user() + "@" + ctx.config().host()
					+ ".\nPlease enter your sudo password to unlock sudo permissions:";
			val password = review.askPassword(ctx.name(), prompt, errorMsg);
			if (password == null) {
				ctx.audit().log("SUDO-CANCELLED", ctx.config().user() + "@" + ctx.config().host());
				return false;
			}
			val authRes = ctx.ssh().exec("sudo -S -p '' -v", 15, (password + "\n").getBytes(StandardCharsets.UTF_8));
			if (authRes.exitCode() == 0) {
				ctx.audit().log("SUDO-VALIDATED", ctx.config().user() + "@" + ctx.config().host());
				return true;
			}
			errorMsg = "Authentication failed (incorrect password). Please try again.";
			ctx.audit().log("SUDO-FAILED-ATTEMPT", "Attempt " + (attempt + 1) + " failed for " + ctx.config().user() + "@" + ctx.config().host());
		}
		return false;
	}

	private static String formatExec(ExecResult r, int timeout) {
		StringBuilder sb = new StringBuilder();
		if (r.timedOut()) sb.append("[TIMEOUT after ").append(timeout).append("s - command was aborted]\n");
		sb.append("exit code: ").append(r.exitCode()).append('\n');
		sb.append("--- stdout ---\n").append(r.stdout());
		if (!r.stdout().endsWith("\n")) sb.append('\n');
		if (r.stdoutTruncated()) sb.append("[stdout truncated]\n");
		sb.append("--- stderr ---\n").append(r.stderr());
		if (!r.stderr().endsWith("\n")) sb.append('\n');
		if (r.stderrTruncated()) sb.append("[stderr truncated]\n");
		return sb.toString();
	}

	// ---------- Files ----------

	private ToolResult readFile(JsonNode a) throws Exception {
		ServerContext ctx;
		try {
			ctx = resolveServer(a);
		} catch (IllegalArgumentException e) {
			return ToolResult.error(e.getMessage());
		}

		String path = resolvePath(requireString(a, "path"));
		if (ctx.policy().isDenied(path)) {
			ctx.audit().log("READ-BLOCKED", path);
			return ToolResult.error(DENIED_MSG);
		}
		ctx.audit().log("READ", path);
		val data = ctx.ssh().readFile(path, ctx.config().maxFileBytes());
		String text;
		try {
			text = decodeUtf8(data);
		} catch (CharacterCodingException e) {
			return ToolResult.error("The file is not valid UTF-8 text (binary file?).");
		}
		return deliver(ctx, "Read file: " + path, text, true);
	}

	private ToolResult listDir(JsonNode a) throws Exception {
		ServerContext ctx;
		try {
			ctx = resolveServer(a);
		} catch (IllegalArgumentException e) {
			return ToolResult.error(e.getMessage());
		}

		val path = resolvePath(a.hasNonNull("path") ? a.get("path").asText() : ".");
		if (ctx.policy().isDenied(path)) {
			ctx.audit().log("LIST-BLOCKED", path);
			return ToolResult.error(DENIED_MSG);
		}
		ctx.audit().log("LIST", path);
		StringBuilder sb = new StringBuilder();
		val base = path.endsWith("/") ? path : path + "/";
		for (SshConnection.Entry e : ctx.ssh().list(path)) {
			if (e.name().equals(".") || e.name().equals("..")) continue;
			if (ctx.policy().isDenied(base + e.name())) continue; // blocked entries remain invisible
			sb.append(e.directory() ? "d " : "- ")
					.append(e.name()).append(e.directory() ? "/" : "")
					.append("  ").append(e.directory() ? "" : e.size() + " B  ")
					.append(Instant.ofEpochSecond(e.mtimeEpochSeconds())).append('\n');
		}
		return deliver(ctx, "Directory: " + path, sb.isEmpty() ? "(empty)\n" : sb.toString(), true);
	}

	private ToolResult editFile(JsonNode a) throws Exception {
		ServerContext ctx;
		try {
			ctx = resolveServer(a);
		} catch (IllegalArgumentException e) {
			return ToolResult.error(e.getMessage());
		}

		String path = resolvePath(requireString(a, "path"));
		val oldS = requireString(a, "old_string");
		val newS = requireString(a, "new_string");
		val all = a.path("replace_all").asBoolean(false);
		if (oldS.isEmpty()) return ToolResult.error("old_string must not be empty.");
		if (oldS.contains(Redactor.REPLACEMENT) || newS.contains(Redactor.REPLACEMENT))
			return ToolResult.error(PLACEHOLDER_MSG);
		if (ctx.policy().isDenied(path)) {
			ctx.audit().log("EDIT-BLOCKED", path);
			return ToolResult.error(DENIED_MSG);
		}

		val before = ctx.ssh().readFile(path, ctx.config().maxFileBytes());
		String oldText;
		try {
			oldText = decodeUtf8(before);
		} catch (CharacterCodingException e) {
			return ToolResult.error("The file is not valid UTF-8 text (binary file?).");
		}
		val count = countOccurrences(oldText, oldS);
		if (count == 0) {
			return ToolResult.error("old_string was not found in the file. (If the content you saw was redacted by the user, "
					+ "choose a section without redacted parts.)");
		}
		if (count > 1 && !all) {
			return ToolResult.error("old_string occurs " + count + " times. Make it unique or set replace_all=true.");
		}
		String newText;
		if (all) {
			newText = oldText.replace(oldS, newS);
		} else {
			int i = oldText.indexOf(oldS);
			newText = oldText.substring(0, i) + newS + oldText.substring(i + oldS.length());
		}
		return applyChange(ctx, path, before, oldText, newText, false, count + " replacement(s)");
	}

	private ToolResult writeFile(JsonNode a) throws Exception {
		ServerContext ctx;
		try {
			ctx = resolveServer(a);
		} catch (IllegalArgumentException e) {
			return ToolResult.error(e.getMessage());
		}

		String path = resolvePath(requireString(a, "path"));
		val content = requireString(a, "content");
		if (content.contains(Redactor.REPLACEMENT)) return ToolResult.error(PLACEHOLDER_MSG);
		if (ctx.policy().isDenied(path)) {
			ctx.audit().log("WRITE-BLOCKED", path);
			return ToolResult.error(DENIED_MSG);
		}

		val before = ctx.ssh().readFileIfExists(path, ctx.config().maxFileBytes());
		String oldText;
		try {
			oldText = before == null ? "" : decodeUtf8(before);
		} catch (CharacterCodingException e) {
			return ToolResult.error("The existing file is not valid UTF-8 text; refusing to overwrite it.");
		}
		return applyChange(ctx, path, before, oldText, content, before == null, before == null ? "new file" : "overwritten");
	}

	/**
	 * Diff approval, race check, local backup, write.
	 */
	private ToolResult applyChange(ServerContext ctx, String path, byte[] beforeBytes, String oldText, String newText,
								   boolean newFile, String summary) throws Exception {
		if (oldText.equals(newText)) return ToolResult.ok("No change: the resulting content is identical.");
		ctx.audit().log("EDIT-PROPOSED", path);
		val d = review.approveEdit(new EditRequest(ctx.name(), path, oldText, newText, newFile));
		if (!d.approved()) {
			ctx.audit().log("EDIT-REJECTED", path);
			return ToolResult.error("The user rejected this change on " + ctx.name() + ". The file was NOT modified." + reasonSuffix(d.reason()));
		}
		if (newFile) {
			if (ctx.ssh().exists(path))
				return ToolResult.error("The file appeared on the server while waiting for approval; nothing was written.");
		} else {
			val now = ctx.ssh().readFile(path, ctx.config().maxFileBytes());
			if (!Arrays.equals(now, beforeBytes)) {
				return ToolResult.error("The file changed on the server while waiting for approval; nothing was written. Re-read it and try again.");
			}
			ctx.backups().save(path, beforeBytes);
		}
		ctx.ssh().writeFile(path, newText.getBytes(StandardCharsets.UTF_8));
		ctx.audit().log("EDIT-APPLIED", path);
		return ToolResult.ok("File written on [" + ctx.name() + "]: " + path + " (" + summary + ")");
	}

	private ToolResult askUser(JsonNode a) throws Exception {
		val answer = review.askUser(requireString(a, "question"));
		return answer == null || answer.isBlank()
				? ToolResult.ok("The user did not provide an answer.")
				: ToolResult.ok(answer);
	}

	// ---------- Output review ----------

	private ToolResult deliver(ServerContext ctx, String title, String text, boolean forceReview) throws InterruptedException {
		val red = ctx.redactions().apply(text);
		boolean mustReview = forceReview || !red.warnings().isEmpty();
		if (!mustReview) return ToolResult.ok(red.text());
		val warning = red.warnings().isEmpty() ? null : String.join("\n", red.warnings());
		val d = review.reviewOutput(new OutputRequest(ctx.name(), title, text, red.text(), red.count(), warning, ctx.redactions()));
		if (!d.send()) {
			ctx.audit().log("OUTPUT-WITHHELD", title);
			return ToolResult.error("The user withheld this output on " + ctx.name() + "." + reasonSuffix(d.reason()));
		}
		ctx.audit().log("OUTPUT-SENT", title);
		return ToolResult.ok(d.text());
	}

	// ---------- Helper functions ----------

	private static String resolvePath(String p) {
		if (p == null || p.isBlank()) return ".";
		return p.trim();
	}

	private static String decodeUtf8(byte[] b) throws CharacterCodingException {
		return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(b))
				.toString();
	}

	private static int countOccurrences(String text, String sub) {
		int count = 0, idx = 0;
		while ((idx = text.indexOf(sub, idx)) != -1) {
			count++;
			idx += sub.length();
		}
		return count;
	}

	private static String reasonSuffix(String reason) {
		return (reason == null || reason.isBlank()) ? "" : " (Reason: " + reason + ")";
	}

	private static String shorten(String s) {
		String one = s.replaceAll("\\s+", " ").trim();
		return one.length() > 60 ? one.substring(0, 57) + "..." : one;
	}

	private static String requireString(JsonNode a, String field) {
		if (a == null || !a.hasNonNull(field)) throw new IllegalArgumentException("Parameter '" + field + "' is required.");
		return a.get(field).asText();
	}

	private static String optString(JsonNode a, String field) {
		return a != null && a.hasNonNull(field) ? a.get(field).asText() : null;
	}

	private record Prop(String name, String type, String description, boolean required) {
	}

	private static Prop prop(String name, String type, String description, boolean required) {
		return new Prop(name, type, description, required);
	}

	private static ObjectNode schema(Prop... props) {
		val root = JsonNodeFactory.instance.objectNode();
		root.put("type", "object");
		val properties = root.putObject("properties");
		val required = root.putArray("required");
		for (Prop p : props) {
			val n = properties.putObject(p.name());
			n.put("type", p.type());
			n.put("description", p.description());
			if (p.required()) required.add(p.name());
		}
		return root;
	}
}
