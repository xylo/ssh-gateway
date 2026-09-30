package de.sshgateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.sshgateway.config.GatewayConfig;
import de.sshgateway.redaction.RedactionStore;
import de.sshgateway.redaction.Redactor;
import de.sshgateway.review.ReviewService;
import de.sshgateway.review.ReviewService.CommandDecision;
import de.sshgateway.review.ReviewService.CommandRequest;
import de.sshgateway.review.ReviewService.EditDecision;
import de.sshgateway.review.ReviewService.EditRequest;
import de.sshgateway.review.ReviewService.OutputDecision;
import de.sshgateway.review.ReviewService.OutputRequest;
import de.sshgateway.security.AuditLog;
import de.sshgateway.security.Backups;
import de.sshgateway.security.PathPolicy;
import de.sshgateway.security.RiskAnalyzer;
import de.sshgateway.ssh.ExecResult;
import de.sshgateway.ssh.SshConnection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The tools that Claude sees. Every action goes through a blocklist, approval, and output review.
 */
public final class GatewayTools {

	public static final String INSTRUCTIONS = """
			This server is a gateway to one remote Linux server. A human reviews everything you do:
			- Every command must be approved by the user; it may be rejected (you will get the reason). Do not retry rejected commands unchanged.
			- Command output and file contents are reviewed by the user first. Sensitive parts are replaced by the placeholder \
			'*** SENSIBLE INFORMATION ***'. Never write this placeholder into files and never guess the hidden values.
			- File changes are shown to the user as a diff and may be rejected.
			- Some paths (secrets, keys, .env files) are blocked entirely.
			- Prefer read-only commands to investigate before changing anything. Use the ask_user tool if requirements are unclear.
			- Relative paths are relative to the SSH user's home directory.
			""";

	private static final String DENIED_MSG = "Access to this path is blocked by the gateway's deny list.";
	private static final String PLACEHOLDER_MSG = "The text contains the redaction placeholder '" + Redactor.REPLACEMENT
			+ "'. Redacted values cannot be written back. Choose old_string/new_string/content without redacted parts.";

	private final GatewayConfig cfg;
	private final SshConnection ssh;
	private final RedactionStore store;
	private final ReviewService review;
	private final PathPolicy policy;
	private final RiskAnalyzer risk;
	private final Backups backups;
	private final AuditLog audit;
	private final List<Pattern> blocked = new ArrayList<>();

	public GatewayTools(GatewayConfig cfg, SshConnection ssh, RedactionStore store, ReviewService review,
											PathPolicy policy, RiskAnalyzer risk, Backups backups, AuditLog audit) {
		this.cfg = cfg;
		this.ssh = ssh;
		this.store = store;
		this.review = review;
		this.policy = policy;
		this.risk = risk;
		this.backups = backups;
		this.audit = audit;
		for (String p : cfg.blockedCommandPatterns()) blocked.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
	}

	public List<ToolDef> definitions() {
		return List.of(
				new ToolDef("run_command",
						"Run a shell command on the remote server. The user must approve every command and reviews its output before you see it.",
						schema(prop("command", "string", "The shell command to run", true),
								prop("description", "string", "One sentence explaining to the user why this command is needed", false),
								prop("timeout_seconds", "integer", "Timeout in seconds (default " + cfg.defaultTimeoutSeconds() + ", max 3600)", false)),
						this::runCommand),
				new ToolDef("read_file",
						"Read a text file from the remote server. The user reviews (and may redact) the content before you receive it.",
						schema(prop("path", "string", "Absolute path or path relative to the home directory", true)),
						this::readFile),
				new ToolDef("edit_file",
						"Replace exact text in a remote file. The user sees a diff and must approve. old_string must match exactly once unless replace_all is true.",
						schema(prop("path", "string", "File path", true),
								prop("old_string", "string", "Exact text to replace (must not contain redacted placeholders)", true),
								prop("new_string", "string", "Replacement text", true),
								prop("replace_all", "boolean", "Replace all occurrences (default false)", false)),
						this::editFile),
				new ToolDef("write_file",
						"Create or completely overwrite a remote text file. The user sees a diff and must approve. Prefer edit_file for small changes.",
						schema(prop("path", "string", "File path", true),
								prop("content", "string", "Complete new file content", true)),
						this::writeFile),
				new ToolDef("list_dir",
						"List a remote directory. The user reviews the listing before you receive it.",
						schema(prop("path", "string", "Directory path (default: home directory)", false)),
						this::listDir),
				new ToolDef("ask_user",
						"Ask the user a clarifying question in a dialog and wait for the answer.",
						schema(prop("question", "string", "The question", true)),
						this::askUser));
	}

	// ---------- run_command ----------

	private ToolResult runCommand(JsonNode a) throws Exception {
		String command = requireString(a, "command");
		String description = optString(a, "description");
		int timeout = Math.clamp(a.path("timeout_seconds").asInt(cfg.defaultTimeoutSeconds()), 1, 3600);

		for (Pattern p : blocked) {
			if (p.matcher(command).find()) {
				audit.log("COMMAND-BLOCKED", command);
				return ToolResult.error("This command matches a blocking rule of the gateway and was not executed.");
			}
		}
		audit.log("COMMAND-PROPOSED", command);
		CommandDecision d = review.approveCommand(new CommandRequest(command, description, risk.analyze(command)));
		if (d.action() == CommandDecision.Action.REJECT) {
			audit.log("COMMAND-REJECTED", command);
			return ToolResult.error("The user rejected this command. It was NOT executed." + reasonSuffix(d.reason()));
		}
		audit.log(d.action() == CommandDecision.Action.EXECUTE_HELD ? "COMMAND-EXECUTED-HELD" : "COMMAND-EXECUTED", command);
		ExecResult r = ssh.exec(command, timeout);
		return deliver("Output from: " + shorten(command), formatExec(r, timeout),
				d.action() == CommandDecision.Action.EXECUTE_HELD);
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
		String path = resolvePath(requireString(a, "path"));
		if (isDenied(path)) {
			audit.log("READ-BLOCKED", path);
			return ToolResult.error(DENIED_MSG);
		}
		audit.log("READ", path);
		byte[] data = ssh.readFile(path, cfg.maxFileBytes());
		String text;
		try {
			text = decodeUtf8(data);
		} catch (CharacterCodingException e) {
			return ToolResult.error("The file is not valid UTF-8 text (binary file?).");
		}
		return deliver("Read file: " + path, text, true);
	}

	private ToolResult listDir(JsonNode a) throws Exception {
		String path = resolvePath(a.hasNonNull("path") ? a.get("path").asText() : ".");
		if (isDenied(path)) {
			audit.log("LIST-BLOCKED", path);
			return ToolResult.error(DENIED_MSG);
		}
		audit.log("LIST", path);
		StringBuilder sb = new StringBuilder();
		String base = path.endsWith("/") ? path : path + "/";
		for (SshConnection.Entry e : ssh.list(path)) {
			if (e.name().equals(".") || e.name().equals("..")) continue;
			if (policy.isDenied(base + e.name())) continue; // blocked entries remain invisible
			sb.append(e.directory() ? "d " : "- ")
					.append(e.name()).append(e.directory() ? "/" : "")
					.append("  ").append(e.directory() ? "" : e.size() + " B  ")
					.append(Instant.ofEpochSecond(e.mtimeEpochSeconds())).append('\n');
		}
		return deliver("Directory: " + path, sb.isEmpty() ? "(empty)\n" : sb.toString(), true);
	}

	private ToolResult editFile(JsonNode a) throws Exception {
		String path = resolvePath(requireString(a, "path"));
		String oldS = requireString(a, "old_string");
		String newS = requireString(a, "new_string");
		boolean all = a.path("replace_all").asBoolean(false);
		if (oldS.isEmpty()) return ToolResult.error("old_string must not be empty.");
		if (oldS.contains(Redactor.REPLACEMENT) || newS.contains(Redactor.REPLACEMENT))
			return ToolResult.error(PLACEHOLDER_MSG);
		if (isDenied(path)) {
			audit.log("EDIT-BLOCKED", path);
			return ToolResult.error(DENIED_MSG);
		}

		byte[] before = ssh.readFile(path, cfg.maxFileBytes());
		String oldText;
		try {
			oldText = decodeUtf8(before);
		} catch (CharacterCodingException e) {
			return ToolResult.error("The file is not valid UTF-8 text (binary file?).");
		}
		int count = countOccurrences(oldText, oldS);
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
		return applyChange(path, before, oldText, newText, false, count + " replacement(s)");
	}

	private ToolResult writeFile(JsonNode a) throws Exception {
		String path = resolvePath(requireString(a, "path"));
		String content = requireString(a, "content");
		if (content.contains(Redactor.REPLACEMENT)) return ToolResult.error(PLACEHOLDER_MSG);
		if (isDenied(path)) {
			audit.log("WRITE-BLOCKED", path);
			return ToolResult.error(DENIED_MSG);
		}

		byte[] before = ssh.readFileIfExists(path, cfg.maxFileBytes());
		String oldText;
		try {
			oldText = before == null ? "" : decodeUtf8(before);
		} catch (CharacterCodingException e) {
			return ToolResult.error("The existing file is not valid UTF-8 text; refusing to overwrite it.");
		}
		return applyChange(path, before, oldText, content, before == null, before == null ? "new file" : "overwritten");
	}

	/**
	 * Diff approval, race check, local backup, write.
	 */
	private ToolResult applyChange(String path, byte[] beforeBytes, String oldText, String newText,
																 boolean newFile, String summary) throws Exception {
		if (oldText.equals(newText)) return ToolResult.ok("No change: the resulting content is identical.");
		audit.log("EDIT-PROPOSED", path);
		EditDecision d = review.approveEdit(new EditRequest(path, oldText, newText, newFile));
		if (!d.approved()) {
			audit.log("EDIT-REJECTED", path);
			return ToolResult.error("The user rejected this change. The file was NOT modified." + reasonSuffix(d.reason()));
		}
		if (newFile) {
			if (ssh.exists(path))
				return ToolResult.error("The file appeared on the server while waiting for approval; nothing was written.");
		} else {
			byte[] now = ssh.readFile(path, cfg.maxFileBytes());
			if (!Arrays.equals(now, beforeBytes)) {
				return ToolResult.error("The file changed on the server while waiting for approval; nothing was written. Re-read it and try again.");
			}
			backups.save(path, beforeBytes);
		}
		ssh.writeFile(path, newText.getBytes(StandardCharsets.UTF_8));
		audit.log("EDIT-APPLIED", path);
		return ToolResult.ok("File written: " + path + " (" + summary + ")");
	}

	private ToolResult askUser(JsonNode a) throws Exception {
		String answer = review.askUser(requireString(a, "question"));
		return answer == null || answer.isBlank()
				? ToolResult.ok("The user did not provide an answer.")
				: ToolResult.ok(answer);
	}

	// ---------- Output review ----------

	private ToolResult deliver(String title, String text, boolean forceReview) throws InterruptedException {
		RedactionStore.Result red = store.apply(text);
		boolean mustReview = forceReview || !red.warnings().isEmpty();
		if (!mustReview) return ToolResult.ok(red.text());
		String warning = red.warnings().isEmpty() ? null : String.join("\n", red.warnings());
		OutputDecision d = review.reviewOutput(new OutputRequest(title, text, red.text(), red.count(), warning));
		if (!d.send()) {
			audit.log("OUTPUT-WITHHELD", title);
			return ToolResult.error("The user withheld this output." + reasonSuffix(d.reason()));
		}
		audit.log("OUTPUT-SENT", title);
		return ToolResult.ok(d.text());
	}

	// ---------- Helper functions ----------

	private boolean isDenied(String path) {
		if (policy.isDenied(path)) return true;
		try {
			return policy.isDenied(ssh.canonicalize(path)); // Resolve symlinks
		} catch (IOException e) {
			return false; // e.g. file does not exist (yet)
		}
	}

	private static String resolvePath(String p) {
		p = p.strip();
		if (p.equals("~")) return ".";
		if (p.startsWith("~/")) return p.substring(2);
		return p.isEmpty() ? "." : p;
	}

	private static String decodeUtf8(byte[] data) throws CharacterCodingException {
		return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(data)).toString();
	}

	private static int countOccurrences(String text, String needle) {
		int count = 0, i = 0;
		while ((i = text.indexOf(needle, i)) >= 0) {
			count++;
			i += needle.length();
		}
		return count;
	}

	private static String reasonSuffix(String reason) {
		return reason == null || reason.isBlank() ? "" : " Reason given by the user: " + reason.strip();
	}

	private static String shorten(String s) {
		String one = s.replace('\n', ' ');
		return one.length() > 80 ? one.substring(0, 77) + "..." : one;
	}

	private static String requireString(JsonNode a, String key) {
		JsonNode n = a.get(key);
		if (n == null || !n.isTextual()) throw new IllegalArgumentException("Missing or invalid argument: " + key);
		return n.asText();
	}

	private static String optString(JsonNode a, String key) {
		JsonNode n = a.get(key);
		return n != null && n.isTextual() ? n.asText() : null;
	}

	private record Prop(String name, String type, String description, boolean required) {
	}

	private static Prop prop(String name, String type, String description, boolean required) {
		return new Prop(name, type, description, required);
	}

	private static ObjectNode schema(Prop... props) {
		ObjectNode root = JsonNodeFactory.instance.objectNode();
		root.put("type", "object");
		ObjectNode properties = root.putObject("properties");
		ArrayNode required = root.putArray("required");
		for (Prop p : props) {
			ObjectNode n = properties.putObject(p.name());
			n.put("type", p.type());
			n.put("description", p.description());
			if (p.required()) required.add(p.name());
		}
		if (required.isEmpty()) root.remove("required");
		return root;
	}
}
