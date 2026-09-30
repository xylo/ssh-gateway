package de.sshgateway;

import de.sshgateway.bridge.BridgeMain;
import de.sshgateway.ui.UiMain;

/**
 * Two operating modes:
 * - without arguments: UI application (JavaFX, SSH, approval dialogs) – started manually by the user.
 * - "--bridge": lightweight stdio bridge to the UI application – started by Claude Desktop.
 */
public final class Main {
	private Main() {
	}

	static void main(String[] args) throws Exception {
		boolean bridge = args.length > 0 && ("--bridge".equals(args[0]) || "-b".equals(args[0]));
		if (bridge) {
			BridgeMain.run();
		} else {
			UiMain.run();
		}
	}
}
