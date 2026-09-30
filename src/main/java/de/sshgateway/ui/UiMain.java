package de.sshgateway.ui;

import de.sshgateway.config.GatewayConfig;
import de.sshgateway.mcp.GatewayTools;
import de.sshgateway.mcp.McpServer;
import de.sshgateway.mcp.ToolDef;
import de.sshgateway.redaction.RedactionStore;
import de.sshgateway.review.ReviewService;
import de.sshgateway.security.AuditLog;
import de.sshgateway.security.Backups;
import de.sshgateway.security.BridgeToken;
import de.sshgateway.security.PathPolicy;
import de.sshgateway.security.RiskAnalyzer;
import de.sshgateway.ssh.SshConnection;
import javafx.application.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Runs continuously in the user's normal desktop session (started manually, e.g. via double-click).
 * Contains the complete logic including JavaFX dialogs and listens on a local TCP port for the bridge
 * (see {@link de.sshgateway.bridge.BridgeMain}), instead of communicating directly over stdio.
 */
public final class UiMain {
	private UiMain() {
	}

	public static void run() throws Exception {
		Path home = GatewayConfig.homeDir();
		Files.createDirectories(home);
		GatewayConfig cfg = GatewayConfig.load(home);

		CountDownLatch fxReady = new CountDownLatch(1);
		Platform.startup(fxReady::countDown);
		fxReady.await();
		Platform.setImplicitExit(false);

		RedactionStore store = new RedactionStore(home.resolve("redactions.json"));
		PathPolicy policy = new PathPolicy(cfg.denyPaths());
		RiskAnalyzer risk = new RiskAnalyzer(cfg.extraRiskPatterns(), policy.literalHints());
		ReviewService review = new FxReviewService(store, cfg.ideaCommand());
		AuditLog audit = new AuditLog(home.resolve("audit.log"));

		Path tokenFile = home.resolve("bridge.token");
		String token = BridgeToken.generate();
		BridgeToken.write(tokenFile, token);

		try (SshConnection ssh = new SshConnection(cfg)) {
			GatewayTools gatewayTools = new GatewayTools(cfg, ssh, store, review, policy, risk,
					new Backups(home.resolve("backups")), audit);
			List<ToolDef> defs = gatewayTools.definitions();

			InetAddress loopback = InetAddress.getLoopbackAddress();
			try (ServerSocket server = new ServerSocket(cfg.bridgePort(), 1, loopback)) {
				System.out.println("ssh-gateway UI ready for " + cfg.user() + "@" + cfg.host());
				System.out.println("Configuration: " + home);
				System.out.println("Bridge token: " + tokenFile);
				System.out.println("Waiting for bridge connections on " + loopback.getHostAddress() + ":" + server.getLocalPort() + " ...");
				System.out.println("(This window/console must remain open as long as Claude needs to use the server.)");

				while (true) {
					Socket socket = server.accept();
					System.out.println("Bridge connected: " + socket.getRemoteSocketAddress());
					try {
						handleConnection(socket, token, defs);
					} catch (IOException e) {
						System.out.println("Connection closed: " + e.getMessage());
					}
					System.out.println("Bridge disconnected – waiting for next connection.");
				}
			}
		} finally {
			Platform.exit();
		}
	}

	private static void handleConnection(Socket socket, String expectedToken, List<ToolDef> defs) throws IOException {
		socket.setTcpNoDelay(true);
		InputStream in = socket.getInputStream();
		String received = BridgeToken.readLine(in);
		if (received == null || !BridgeToken.matches(received, expectedToken)) {
			System.out.println("Connection rejected: invalid or missing token.");
			socket.close();
			return;
		}
		PrintStream out = new PrintStream(socket.getOutputStream(), true, StandardCharsets.UTF_8);
		new McpServer(out, defs, GatewayTools.INSTRUCTIONS).run(in);
	}
}
