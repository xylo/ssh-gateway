package de.sshgateway.review;

import de.sshgateway.redaction.RedactionStore;

import java.util.List;

/**
 * Interface for user decisions. The implementation (JavaFX) is interchangeable –
 * later, for example, with an IntelliJ plugin. All methods block until the user has made a decision.
 */
public interface ReviewService {

	record CommandRequest(String server, String command, String description, List<String> riskFlags) {
	}

	record CommandDecision(Action action, String reason) {
		public enum Action {EXECUTE, EXECUTE_HELD, REJECT}
	}

	/**
	 * @param server           the target server name
	 * @param rawText          unmodified output
	 * @param initialText      output after automatic replacements (initial editor content)
	 * @param autoReplacements number of automatic replacements made
	 * @param warning          note (e.g., rule could not be applied) or null
	 * @param store            the server's redaction store for saving "Always replace" rules
	 */
	record OutputRequest(String server, String title, String rawText, String initialText, int autoReplacements, String warning, RedactionStore store) {
	}

	record OutputDecision(boolean send, String text, String reason) {
	}

	record EditRequest(String server, String path, String oldText, String newText, boolean newFile) {
	}

	record EditDecision(boolean approved, String reason) {
	}

	CommandDecision approveCommand(CommandRequest request) throws InterruptedException;

	OutputDecision reviewOutput(OutputRequest request) throws InterruptedException;

	EditDecision approveEdit(EditRequest request) throws InterruptedException;

	/**
	 * @return the user's response, or null if they chose not to respond
	 */
	String askUser(String question) throws InterruptedException;

	/**
	 * Requests the sudo password from the user for the given server.
	 *
	 * @param server       the server name
	 * @param prompt       the prompt message explaining why the password is requested
	 * @param errorMessage an optional error message (e.g. if the previous attempt failed) or null
	 * @return the password entered by the user, or null if cancelled
	 */
	String askPassword(String server, String prompt, String errorMessage) throws InterruptedException;
}
