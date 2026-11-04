package de.sshgateway.ui;

import de.sshgateway.config.GatewayConfig;
import de.sshgateway.config.ServerConfig;
import de.sshgateway.mcp.GatewayTools;
import de.sshgateway.mcp.McpServer;
import de.sshgateway.mcp.ToolDef;
import de.sshgateway.security.BridgeToken;
import de.sshgateway.server.ServerContext;
import javafx.application.Platform;
import lombok.val;

import java.awt.Color;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
		val home = GatewayConfig.homeDir();
		Files.createDirectories(home);
		val cfg = GatewayConfig.load(home);

		setupTrayIcon(cfg);

		val fxReady = new CountDownLatch(1);
		Platform.startup(fxReady::countDown);
		fxReady.await();
		Platform.setImplicitExit(false);

		val review = new FxReviewService(cfg.ideaCommand());
		val tokenFile = home.resolve("bridge.token");
		val token = BridgeToken.generate();
		BridgeToken.write(tokenFile, token);

		Map<String, ServerContext> serverContexts = new LinkedHashMap<>();
		for (ServerConfig sc : cfg.servers().values()) {
			serverContexts.put(sc.name(), ServerContext.create(home, sc));
		}

		try {
			val gatewayTools = new GatewayTools(serverContexts, cfg.defaultServer(), review);
			val defs = gatewayTools.definitions();
			val instructions = GatewayTools.buildInstructions(cfg.servers(), cfg.defaultServer());

			val loopback = InetAddress.getLoopbackAddress();
			try (ServerSocket server = new ServerSocket(cfg.bridgePort(), 50, loopback)) {
				System.out.println("ssh-gateway UI ready for " + (serverContexts.size() == 1 ? "1 server:" : serverContexts.size() + " servers:"));
				for (ServerContext sc : serverContexts.values()) {
					System.out.println("  - " + sc.name() + ": " + sc.config().user() + "@" + sc.config().host() + ":" + sc.config().port()
							+ (sc.name().equals(cfg.defaultServer()) ? " [default]" : ""));
				}
				System.out.println("Configuration directory: " + home);
				System.out.println("Bridge token: " + tokenFile);
				System.out.println("Waiting for bridge connections on " + loopback.getHostAddress() + ":" + server.getLocalPort() + " ...");
				System.out.println("(This window/console must remain open as long as Claude needs to use the server.)");

				while (true) {
					val socket = server.accept();
					System.out.println("Bridge connected: " + socket.getRemoteSocketAddress());
					Thread.ofVirtual().name("bridge-session-", 0).start(() -> {
						try (socket) {
							handleConnection(socket, token, defs, instructions);
						} catch (IOException e) {
							System.out.println("Connection closed: " + e.getMessage());
						} finally {
							System.out.println("Bridge disconnected: " + socket.getRemoteSocketAddress());
						}
					});
				}
			}
		} finally {
			for (ServerContext ctx : serverContexts.values()) {
				try {
					ctx.close();
				} catch (Exception ignored) {
				}
			}
			Platform.exit();
		}
	}

	/**
	 * Sets up the system tray icon with a tray menu to close the app.
	 */
	private static void setupTrayIcon(GatewayConfig cfg) {
		if (!SystemTray.isSupported()) {
			return;
		}
		val tray = SystemTray.getSystemTray();
		val trayIcon = getTrayIcon();
		String tooltip = cfg.servers().size() == 1
				? "SSH Gateway – " + cfg.servers().values().iterator().next().displayName()
				: "SSH Gateway (" + cfg.servers().size() + " servers)";
		trayIcon.setToolTip(tooltip);

		val popup = new PopupMenu();
		val exitItem = new MenuItem("Exit");
		exitItem.addActionListener(e -> System.exit(0));
		popup.add(exitItem);

		trayIcon.setPopupMenu(popup);
		try {
			tray.add(trayIcon);
		} catch (Exception e) {
			System.err.println("Could not add tray icon: " + e);
		}
	}

	/**
	 * Creates a tray icon with a server symbol.
	 *
	 * @return tray icon
	 */
	private static TrayIcon getTrayIcon() {
		val image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
		val g2d = image.createGraphics();

		// Background
		g2d.setColor(new Color(45, 45, 48)); // Dark grey background
		g2d.fillRect(0, 0, 16, 16);

		// Server body
		g2d.setColor(new Color(100, 100, 100));
		g2d.fillRect(3, 4, 10, 8);

		// Server lines (slots)
		g2d.setColor(new Color(180, 180, 180));
		g2d.drawLine(4, 6, 12, 6);
		g2d.drawLine(4, 8, 12, 8);
		g2d.drawLine(4, 10, 12, 10);

		// Status indicator (SSH/Active)
		g2d.setColor(Color.CYAN);
		g2d.fillOval(12, 4, 2, 2);

		g2d.dispose();

		return new TrayIcon(image, "SSH Gateway");
	}

	private static void handleConnection(Socket socket, String expectedToken, List<ToolDef> defs, String instructions) throws IOException {
		socket.setTcpNoDelay(true);
		val in = socket.getInputStream();
		val received = BridgeToken.readLine(in);
		if (received == null || !BridgeToken.matches(received, expectedToken)) {
			System.out.println("Connection rejected: invalid or missing token.");
			socket.close();
			return;
		}
		val out = new PrintStream(socket.getOutputStream(), true, StandardCharsets.UTF_8);
		new McpServer(out, defs, instructions).run(in);
	}
}
