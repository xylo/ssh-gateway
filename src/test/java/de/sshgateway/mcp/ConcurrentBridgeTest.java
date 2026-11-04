package de.sshgateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.sshgateway.security.BridgeToken;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class ConcurrentBridgeTest {

	private static final ObjectMapper om = new ObjectMapper();

	@Test
	public void testMultipleBridgesConcurrentCommunication() throws Exception {
		String token = BridgeToken.generate();
		ToolDef pingTool = new ToolDef("test_tool", "A test tool", JsonNodeFactory.instance.objectNode(),
				args -> ToolResult.ok("hello from test_tool"));
		List<ToolDef> defs = List.of(pingTool);

		InetAddress loopback = InetAddress.getLoopbackAddress();
		try (ServerSocket server = new ServerSocket(0, 50, loopback)) {
			int port = server.getLocalPort();

			CountDownLatch serverReady = new CountDownLatch(1);
			CompletableFuture<Void> serverTask = CompletableFuture.runAsync(() -> {
				serverReady.countDown();
				try {
					while (!server.isClosed()) {
						Socket socket = server.accept();
						Thread.ofVirtual().name("bridge-test-session-", 0).start(() -> {
							try (socket) {
								socket.setTcpNoDelay(true);
								var in = socket.getInputStream();
								String received = BridgeToken.readLine(in);
								if (received != null && BridgeToken.matches(received, token)) {
									var out = new PrintStream(socket.getOutputStream(), true, StandardCharsets.UTF_8);
									new McpServer(out, defs, "Instructions").run(in);
								}
							} catch (IOException ignored) {
							}
						});
					}
				} catch (IOException ignored) {
				}
			});

			assertTrue(serverReady.await(5, TimeUnit.SECONDS));

			// Start two concurrent bridge client connections
			CompletableFuture<JsonNode> bridge1Future = CompletableFuture.supplyAsync(() -> {
				try (Socket s = new Socket(loopback, port)) {
					s.setTcpNoDelay(true);
					OutputStream out = s.getOutputStream();
					BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
					BridgeToken.writeLine(out, token);

					// Send initialize request
					ObjectNode initMsg = om.createObjectNode();
					initMsg.put("jsonrpc", "2.0");
					initMsg.put("id", 1);
					initMsg.put("method", "initialize");
					initMsg.putObject("params").put("protocolVersion", "2025-06-18");
					out.write((om.writeValueAsString(initMsg) + "\n").getBytes(StandardCharsets.UTF_8));
					out.flush();

					String respLine = in.readLine();
					return om.readTree(respLine);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});

			CompletableFuture<JsonNode> bridge2Future = CompletableFuture.supplyAsync(() -> {
				try (Socket s = new Socket(loopback, port)) {
					s.setTcpNoDelay(true);
					OutputStream out = s.getOutputStream();
					BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
					BridgeToken.writeLine(out, token);

					// Send tools/list request
					ObjectNode listMsg = om.createObjectNode();
					listMsg.put("jsonrpc", "2.0");
					listMsg.put("id", 2);
					listMsg.put("method", "tools/list");
					listMsg.putObject("params");
					out.write((om.writeValueAsString(listMsg) + "\n").getBytes(StandardCharsets.UTF_8));
					out.flush();

					String respLine = in.readLine();
					return om.readTree(respLine);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});

			JsonNode resp1 = bridge1Future.get(5, TimeUnit.SECONDS);
			JsonNode resp2 = bridge2Future.get(5, TimeUnit.SECONDS);

			assertNotNull(resp1);
			assertEquals(1, resp1.get("id").asInt());
			assertEquals("ssh-gateway", resp1.path("result").path("serverInfo").path("name").asText());

			assertNotNull(resp2);
			assertEquals(2, resp2.get("id").asInt());
			assertEquals("test_tool", resp2.path("result").path("tools").get(0).path("name").asText());

			server.close();
			serverTask.cancel(true);
		}
	}
}
