package de.sshgateway.bridge;

import de.sshgateway.config.GatewayConfig;
import de.sshgateway.security.BridgeToken;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Lightweight bridge between Claude Desktop (stdio) and the UI application ({@link de.sshgateway.ui.UiMain}, local TCP socket).
 * Only copies bytes – no internal understanding of the MCP protocol, no JavaFX. Thus runs smoothly even as a
 * child process without access to an interactive desktop session.
 */
public final class BridgeMain {
	private static final int RETRY_ATTEMPTS = 20;
	private static final long RETRY_DELAY_MS = 500;

	private BridgeMain() {
	}

	public static void run() {
		// stdout is now exclusively reserved for forwarded protocol bytes.
		PrintStream rawOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
		System.setOut(System.err);

		try {
			Path home = GatewayConfig.homeDir();
			GatewayConfig cfg = GatewayConfig.load(home);
			Path tokenFile = home.resolve("bridge.token");

			String token = waitForToken(tokenFile);
			if (token == null) {
				System.err.println("No token found at " + tokenFile + ". Is the ssh-gateway UI application running "
						+ "(java -jar ssh-gateway.jar, without --bridge)?");
				System.exit(1);
				return;
			}

			Socket socket = connectWithRetry(InetAddress.getLoopbackAddress(), cfg.bridgePort());
			if (socket == null) {
				System.err.println("Could not connect to the ssh-gateway UI on port " + cfg.bridgePort()
						+ ". Please start 'java -jar ssh-gateway.jar' (without --bridge) first and keep it running.");
				System.exit(1);
				return;
			}

			OutputStream toUi = socket.getOutputStream();
			BridgeToken.writeLine(toUi, token);
			InputStream fromUi = socket.getInputStream();

			Thread upstream = new Thread(() -> copy(System.in, toUi, socket), "bridge-to-ui");
			Thread downstream = new Thread(() -> copy(fromUi, rawOut, socket), "ui-to-bridge");
			upstream.setDaemon(true);
			downstream.setDaemon(true);
			upstream.start();
			downstream.start();
			downstream.join(); // End of session once the UI closes the connection
		} catch (Exception e) {
			System.err.println("Bridge error: " + e);
			System.exit(1);
		}
	}

	private static String waitForToken(Path tokenFile) throws InterruptedException {
		for (int i = 0; i < RETRY_ATTEMPTS; i++) {
			try {
				return BridgeToken.read(tokenFile);
			} catch (IOException e) {
				Thread.sleep(RETRY_DELAY_MS);
			}
		}
		return null;
	}

	private static Socket connectWithRetry(InetAddress addr, int port) throws InterruptedException {
		for (int i = 0; i < RETRY_ATTEMPTS; i++) {
			try {
				return new Socket(addr, port);
			} catch (IOException e) {
				Thread.sleep(RETRY_DELAY_MS);
			}
		}
		return null;
	}

	private static void copy(InputStream in, OutputStream out, Socket socket) {
		try (socket) {
			try {
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) != -1) {
					out.write(buf, 0, n);
					out.flush();
				}
			} catch (IOException ignored) {
				// Connection terminated – session is over
			}
		} catch (IOException ignored) {
		}
	}
}
