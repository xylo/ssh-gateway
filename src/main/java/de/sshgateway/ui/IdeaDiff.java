package de.sshgateway.ui;

import java.io.IOException;
import java.nio.file.Path;

import lombok.val;

/**
 * Opens the IntelliJ diff via "idea diff <left> <right>". Display-only, returns no decision.
 */
final class IdeaDiff {
	private IdeaDiff() {
	}

	/**
	 * @return a status message for the dialog
	 */
	static String open(String ideaCommand, Path left, Path right) {
		if (ideaCommand == null || ideaCommand.isBlank()) {
			return "IntelliJ launch is disabled (ideaCommand is empty). Use the text diff below.";
		}
		try {
		val p = new ProcessBuilder(ideaCommand, "diff", left.toString(), right.toString())
					.redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.start();
			p.getOutputStream().close();
			return "IntelliJ diff opened – left: current version on server, right: proposed by Claude.";
		} catch (IOException e) {
			return "IntelliJ could not be launched (" + e.getMessage()
					+ "). Use the text diff below or adjust 'ideaCommand' in config.json.";
		}
	}
}
