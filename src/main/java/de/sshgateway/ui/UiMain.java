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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
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
		setupTrayIcon();
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

	/**
	 * Sets up the system tray icon with a tray menu to close the app.
	 */
	private static void setupTrayIcon() {
		if (!SystemTray.isSupported()) {
			return;
		}
		SystemTray tray = SystemTray.getSystemTray();
		TrayIcon trayIcon = getTrayIcon();
		trayIcon.setToolTip("SSH Gateway");

		PopupMenu popup = new PopupMenu();
		MenuItem exitItem = new MenuItem("Exit");
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
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
		Graphics2D g2d = image.createGraphics();

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

		TrayIcon trayIcon = new TrayIcon(image, "SSH Gateway");
		return trayIcon;
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
