package de.sshgateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record ToolDef(String name, String description, ObjectNode inputSchema, Handler handler) {
	@FunctionalInterface
	public interface Handler {
		ToolResult call(JsonNode arguments) throws Exception;
	}
}
