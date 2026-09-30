package de.sshgateway.mcp;

public record ToolResult(String text, boolean isError) {
	public static ToolResult ok(String text) {
		return new ToolResult(text, false);
	}

	public static ToolResult error(String text) {
		return new ToolResult(text, true);
	}
}
