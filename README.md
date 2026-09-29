# SSH Gateway (MCP) for Claude Code / Claude Desktop

A local gateway that lets Claude manage a server – **you sit in the middle**:

- every command is shown to you first: **Run**, **Run and hold output** (output is withheld until you review it) or **Reject** (with a reason sent back to Claude)
- output and file contents open in an editor with **Replace** (live highlighting of every match, optionally RegEx), **Always replace** (saves the rule and applies it automatically from then on), **Search** (Ctrl+F, F3) and free-form editing
- file changes (`edit_file`, `write_file`) open `idea diff` plus an **Accept / Reject** dialog (with a text diff as a fallback)
- follow-up questions: Claude can ask in chat or via the `ask_user` tool (dialog)

Java 25, Maven, JavaFX + RichTextFX, sshj.

## Architecture: two modes in one jar

On Windows (and partly on other systems too), child processes started by Claude Desktop don't get access to the
interactive desktop session – a JavaFX process started via stdio then can't show a window, without any visible
error. That's why the gateway runs as **two processes**:

| | UI application | Bridge |
|---|---|---|
| Started by | **you**, e.g. double-click or shortcut | Claude Desktop, via `claude_desktop_config.json` |
| Command | `java -jar ssh-gateway.jar` | `java -jar ssh-gateway.jar --bridge` |
| Contains | JavaFX, SSH connection, all approval dialogs | only stdio ↔ local socket byte forwarding |
| Runs in | your normal desktop session → dialogs appear | anywhere, doesn't need desktop access |

The UI application listens on `127.0.0.1:<bridgePort>` (default `51823`) and, on startup, writes a random token to
`~/.ssh-gateway/bridge.token`. The bridge reads that token, connects, and from then on only forwards bytes – it
doesn't understand MCP itself. Benefit: if you restart Claude Desktop, a new bridge simply reconnects; the UI
application (and the SSH connection) doesn't need to be restarted for that.

**Important:** the UI application must be running *before* you use it from Claude, and must keep running for the
whole session.

## Building and starting

```
mvn package
```

The jar contains the JavaFX natives of the build OS – build it on the machine it will run on.

**1. Start the UI application** (stays open, shows status and later the approval dialogs):
```
java -jar target/ssh-gateway.jar
```
Expected output: `ssh-gateway UI bereit für ... / Wartet auf Brücken-Verbindungen auf 127.0.0.1:51823 ...`

**2. Connect Claude Desktop** – see below.

## Configuration

`~/.ssh-gateway/config.json` (alternative location: environment variable `SSHGW_HOME`), template: `config.example.json`.

| Field | Meaning |
|---|---|
| `host`, `port`, `user` | target (required: host, user) |
| `keyFile` | private key; if omitted: default key under `~/.ssh` |
| `knownHostsFile` | default: `~/.ssh/known_hosts` (host must already be listed there → connect once via `ssh` first) |
| `ideaCommand` | default `idea`; on Windows e.g. `idea64.exe`/`idea.bat`; empty = disabled |
| `denyPaths` | replaces the default deny list (`.env`, `*.pem`, `.ssh/**`, `/etc/shadow` …) |
| `extraRiskPatterns` | additional regexes that flag commands as suspicious in the dialog |
| `blockedCommandPatterns` | regexes; matching commands are rejected without a dialog |
| `defaultTimeoutSeconds`, `maxOutputBytes`, `maxFileBytes` | limits (120 s / 1 MB / 512 KB) |
| `bridgePort` | local port between UI and bridge (default `51823`, `127.0.0.1` only) |

Passwords don't belong in this file: use the `SSHGW_PASSWORD` or `SSHGW_KEY_PASSPHRASE` environment variables instead.

Other files under `~/.ssh-gateway/`: `redactions.json` (replacement rules), `audit.log` (actions and decisions,
without output content), `backups/` (originals saved before every file change), `bridge.token` (the bridge's access
token – don't share it).

## Connecting Claude Desktop

`%APPDATA%\Claude\claude_desktop_config.json` (Windows) or the equivalent on macOS/Linux:

```json
{
  "mcpServers": {
    "ssh-gateway": {
      "command": "java",
      "args": [
        "-jar",
        "C:\\Users\\you\\path\\to\\ssh-gateway.jar",
        "--bridge"
      ]
    }
  }
}
```

If Claude Desktop can't find `java` on the PATH, use the full path to `java.exe` instead (`where java` in a console
shows it).

After saving, **fully** quit and restart Claude Desktop (not just close the window). Prerequisite: the UI application
is already running (see above).

## Connecting Claude Code

```
claude mcp add ssh-gateway --scope user -- java -jar /full/path/to/ssh-gateway.jar --bridge
```

Here too, the UI application must already be running separately.

To stop Claude from going around the gateway, add this to the project's `.claude/settings.json`:

```json
{
  "permissions": { "deny": ["Bash(ssh:*)", "Bash(scp:*)", "Bash(rsync:*)", "Bash(sftp:*)"] },
  "env": { "MCP_TOOL_TIMEOUT": "600000" }
}
```

`MCP_TOOL_TIMEOUT` (milliseconds) prevents a tool call from timing out while you're still reviewing it. Check this
value against the current Claude Code docs.

## Behavior in detail

- **Run** still applies the saved rules to the output. If a rule can't be evaluated (invalid regex/timeout), the
  review dialog opens automatically.
- `read_file` and `list_dir` always go through the review dialog.
- The placeholder `*** SENSIBLE INFORMATION ***` is rejected in `edit_file`/`write_file`, so Claude can't write it
  into real files.
- Before writing, the gateway checks whether the file has changed on the server since it was read.
- If Claude Code/Desktop cancels a call, the open dialog closes.
- Multiple concurrent calls are shown one after another as dialogs.
- **If the UI application crashes or restarts**, the currently connected bridge loses its connection and exits.
  Claude Desktop/Code then needs to be reconnected (usually by restarting it) so a new bridge starts.

## Limitations

- The risk flagging and the regex-based redaction are aids, not a security boundary. The actual safeguard is a
  restricted server user (no root, sudo allow-list).
- Anything you approve for Claude is sent to the Anthropic API.
- Symlinks pointing to blocked paths are resolved and blocked; for commands (`run_command`), the deny list can only
  flag a possible match in the dialog, not enforce it.
- The local port is only reachable via `127.0.0.1`. On a multi-user machine, other locally logged-in users could
  theoretically try to connect; the token prevents that, as long as `bridge.token` isn't readable by other accounts
  (enforced via file permissions on Linux/macOS; on Windows, the default protection of the user profile applies).
