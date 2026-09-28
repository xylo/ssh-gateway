package de.sshgateway.review;

import java.util.List;

/**
 * Interface for user decisions. The implementation (JavaFX) is interchangeable –
 * later, for example, with an IntelliJ plugin. All methods block until the user has made a decision.
 */
public interface ReviewService {

    record CommandRequest(String command, String description, List<String> riskFlags) {}

    record CommandDecision(Action action, String reason) {
        public enum Action { EXECUTE, EXECUTE_HELD, REJECT }
    }

    /**
     * @param rawText      unmodified output
     * @param initialText  output after automatic replacements (initial editor content)
     * @param warning      note (e.g., rule could not be applied) or null
     */
    record OutputRequest(String title, String rawText, String initialText, int autoReplacements, String warning) {}

    record OutputDecision(boolean send, String text, String reason) {}

    record EditRequest(String path, String oldText, String newText, boolean newFile) {}

    record EditDecision(boolean approved, String reason) {}

    CommandDecision approveCommand(CommandRequest request) throws InterruptedException;

    OutputDecision reviewOutput(OutputRequest request) throws InterruptedException;

    EditDecision approveEdit(EditRequest request) throws InterruptedException;

    /** @return the user's response, or null if they chose not to respond */
    String askUser(String question) throws InterruptedException;
}
