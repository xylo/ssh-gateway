package de.sshgateway.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal MCP server implementation for stdio (JSON-RPC 2.0, one message per line).
 * Intentionally without SDK: only initialize, ping, tools/list, tools/call, and notifications/cancelled are needed.
 * Tool calls run in dedicated threads so ping/cancel can be answered even during an open dialog.
 */
public final class McpServer {
	private static final List<String> SUPPORTED_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

	private final ObjectMapper om = new ObjectMapper();
	private final PrintStream out;
	private final Map<String, ToolDef> tools = new LinkedHashMap<>();
	private final String instructions;
	private final Map<String, Thread> inflight = new ConcurrentHashMap<>();
	private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

	public McpServer(PrintStream protocolOut, List<ToolDef> toolDefs, String instructions) {
		this.out = protocolOut;
		this.instructions = instructions;
		for (ToolDef t : toolDefs) tools.put(t.name(), t);
	}

	/**
	 * Reads from stdin until EOF.
	 */
	public void run(InputStream in) throws IOException {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank()) continue;
				JsonNode msg;
				try {
					msg = om.readTree(line);
				} catch (JsonProcessingException e) {
					sendError(null, -32700, "Parse error");
					continue;
				}
				try {
					handle(msg);
				} catch (RuntimeException e) {
					System.err.println("Error processing message: " + e);
					if (msg.has("id") && msg.has("method")) sendError(msg.get("id"), -32603, "Internal error: " + e.getMessage());
				}
			}
		} finally {
			pool.shutdownNow();
		}
	}

	private void handle(JsonNode msg) {
		String method = msg.path("method").asText(null);
		if (method == null) return; // Responses to our own requests do not exist
		JsonNode id = msg.get("id");
		JsonNode params = msg.path("params");
		switch (method) {
			case "initialize" -> {
				String requested = params.path("protocolVersion").asText("");
				ObjectNode res = om.createObjectNode();
				res.put("protocolVersion", SUPPORTED_VERSIONS.contains(requested) ? requested : SUPPORTED_VERSIONS.get(0));
				res.putObject("capabilities").putObject("tools").put("listChanged", false);
				ObjectNode info = res.putObject("serverInfo");
				info.put("name", "ssh-gateway");
				info.put("version", "1.0.0");
				res.put("instructions", instructions);
				if (id != null) reply(id, res);
			}
			case "ping" -> {
				if (id != null) reply(id, om.createObjectNode());
			}
			case "tools/list" -> {
				ObjectNode res = om.createObjectNode();
				ArrayNode arr = res.putArray("tools");
				for (ToolDef t : tools.values()) {
					ObjectNode n = arr.addObject();
					n.put("name", t.name());
					n.put("description", t.description());
					n.set("inputSchema", t.inputSchema());
				}
				if (id != null) reply(id, res);
			}
			case "tools/call" -> {
				if (id != null) pool.submit(() -> callTool(id, params));
			}
			case "notifications/cancelled" -> {
				String key = params.path("requestId").asText(null);
				Thread t = key == null ? null : inflight.get(key);
				if (t != null) t.interrupt();
			}
			default -> {
				if (id != null) sendError(id, -32601, "Method not found: " + method);
			}
		}
	}

	private void callTool(JsonNode id, JsonNode params) {
		String key = id.asText();
		inflight.put(key, Thread.currentThread());
		try {
			ToolDef tool = tools.get(params.path("name").asText(""));
			if (tool == null) {
				sendError(id, -32602, "Unknown tool: " + params.path("name").asText(""));
				return;
			}
			ToolResult res;
			try {
				res = tool.handler().call(params.path("arguments"));
			} catch (InterruptedException e) {
				return; // cancelled by client: do not send a response per specification
			} catch (Exception e) {
				System.err.println("Tool error (" + tool.name() + "): " + e);
				res = ToolResult.error("Gateway error: " + e.getMessage());
			}
			ObjectNode result = om.createObjectNode();
			ArrayNode content = result.putArray("content");
			content.addObject().put("type", "text").put("text", res.text());
			result.put("isError", res.isError());
			reply(id, result);
		} finally {
			inflight.remove(key);
			Thread.interrupted(); // Reset interrupt flag
		}
	}

	private void reply(JsonNode id, ObjectNode result) {
		ObjectNode n = om.createObjectNode();
		n.put("jsonrpc", "2.0");
		n.set("id", id);
		n.set("result", result);
		send(n);
	}

	private void sendError(JsonNode id, int code, String message) {
		ObjectNode n = om.createObjectNode();
		n.put("jsonrpc", "2.0");
		if (id == null) n.putNull("id");
		else n.set("id", id);
		ObjectNode err = n.putObject("error");
		err.put("code", code);
		err.put("message", message);
		send(n);
	}

	private synchronized void send(ObjectNode n) {
		try {
			out.print(om.writeValueAsString(n) + "\n");
			out.flush();
		} catch (JsonProcessingException e) {
			System.err.println("Serialization error: " + e);
		}
	}
}
