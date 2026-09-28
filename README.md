# SSH Gateway (MCP) for Claude Code

A local MCP server (stdio) allowing Claude to manage a remote server – **with human-in-the-loop review**:

- Every command is presented to you: **Execute**, **Execute with review** (output is held back and reviewed by you), or **Reject** (with a reason sent back to Claude)
- Output and file contents appear in an editor with **Replace** (live highlighting of all matches, optional RegEx), **Always replace** (rule is saved and applied automatically in the future), **Search** (Ctrl+F, F3), and free text editing
- File modifications (`edit_file`, `write_file`) open `idea diff` alongside an **Accept / Reject** dialog (plus text diff fallback)
- Inquiries: Claude asks in chat or via the `ask_user` tool (dialog)

Java 25, Maven, JavaFX + RichTextFX, sshj. No MCP SDK: the protocol (initialize, tools/list, tools/call, ping, cancel) is implemented natively.

## Build and Run

```
mvn package
java -jar target/ssh-gateway.jar      # normally started by Claude Code, not manually
```

The jar bundles the JavaFX native libraries of the build operating system – build on the target machine where it will run.

## Configuration

`~/.ssh-gateway/config.json` (custom path: `SSHGW_HOME` environment variable), template: `config.example.json`.

| Field | Description |
|---|---|
| `host`, `port`, `user` | Target (required: host, user) |
| `keyFile` | Private key file; if omitted: default key in `~/.ssh` |
| `knownHostsFile` | Default: `~/.ssh/known_hosts` (host must already be present → connect once via `ssh`) |
| `ideaCommand` | Default: `idea`; on Windows e.g. `idea64.exe`/`idea.bat`; empty = disabled |
| `denyPaths` | Overrides the default deny list (`.env`, `*.pem`, `.ssh/**`, `/etc/shadow` …) |
| `extraRiskPatterns` | Additional regex patterns to flag commands as suspicious in the dialog |
| `blockedCommandPatterns` | Regex patterns; matching commands are rejected without showing a dialog |
| `defaultTimeoutSeconds`, `maxOutputBytes`, `maxFileBytes` | Limits (120 s / 1 MB / 512 KB) |

Passwords should not be stored in the file: use `SSHGW_PASSWORD` or `SSHGW_KEY_PASSPHRASE` as environment variables instead.

Additional files in `~/.ssh-gateway/`: `redactions.json` (redaction rules), `audit.log` (actions and decisions, without output content), `backups/` (original files prior to any modification).

## Integrating into Claude Code

```
claude mcp add ssh-gateway --scope user -- java -jar /full/path/to/ssh-gateway.jar
```

To prevent Claude from bypassing the gateway, configure the project's `.claude/settings.json`:

```json
{
  "permissions": { "deny": ["Bash(ssh:*)", "Bash(scp:*)", "Bash(rsync:*)", "Bash(sftp:*)"] },
  "env": { "MCP_TOOL_TIMEOUT": "600000" }
}
```

`MCP_TOOL_TIMEOUT` (milliseconds) prevents tool calls from timing out while you are reviewing. Check this value against the current Claude Code documentation.

## Detailed Behavior

- **Execute** still applies saved redaction rules to the output. If a rule cannot be evaluated (invalid/timeout), the review dialog opens automatically.
- `read_file` and `list_dir` always go through the review dialog.
- The placeholder `*** SENSIBLE INFORMATION ***` is rejected in `edit_file`/`write_file` to prevent Claude from writing it into real files.
- Before writing, the gateway verifies that the file has not changed on the server since it was read.
- If Claude Code cancels a tool call, any open dialog is closed automatically.
- Multiple concurrent calls are presented as sequential dialogs.

## Limitations

- Risk flagging and regex redaction are safety aids, not security boundaries. The actual security foundation is a restricted server user account (non-root, sudo whitelist).
- Everything approved for Claude is sent to the Anthropic API.
- Symlinks to denied paths are resolved and blocked; commands (`run_command`) can only be covered by path hints in the dialog.
