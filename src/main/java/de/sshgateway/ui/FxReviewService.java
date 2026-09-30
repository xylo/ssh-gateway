package de.sshgateway.ui;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import de.sshgateway.redaction.RedactionStore;
import de.sshgateway.review.ReviewService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * JavaFX implementation. Dialogs are shown sequentially (not simultaneously).
 */
public final class FxReviewService implements ReviewService {
	private final RedactionStore store;
	private final String ideaCommand;
	private final ReentrantLock lock = new ReentrantLock(true);

	public FxReviewService(RedactionStore store, String ideaCommand) {
		this.store = store;
		this.ideaCommand = ideaCommand;
	}

	@Override
	public CommandDecision approveCommand(CommandRequest req) throws InterruptedException {
		lock.lockInterruptibly();
		try {
			return FxUtil.ask(new CommandDecision(CommandDecision.Action.REJECT, "Dialog closed without decision."),
					f -> CommandDialog.create(req, f));
		} finally {
			lock.unlock();
		}
	}

	@Override
	public OutputDecision reviewOutput(OutputRequest req) throws InterruptedException {
		lock.lockInterruptibly();
		try {
			return FxUtil.ask(new OutputDecision(false, null, "Dialog closed without decision."),
					f -> OutputReviewDialog.create(req, store, f));
		} finally {
			lock.unlock();
		}
	}

	@Override
	public EditDecision approveEdit(EditRequest req) throws InterruptedException {
		lock.lockInterruptibly();
		Path dir = null;
		try {
			dir = Files.createTempDirectory("sshgw-diff");
			String name = baseName(req.path());
			Path left = dir.resolve("original").resolve(name);
			Path right = dir.resolve("proposal").resolve(name);
			Files.createDirectories(left.getParent());
			Files.createDirectories(right.getParent());
			Files.writeString(left, req.oldText());
			Files.writeString(right, req.newText());

			String status = IdeaDiff.open(ideaCommand, left, right);
			String unified = unifiedDiff(req);
			return FxUtil.ask(new EditDecision(false, "Dialog closed without decision."),
					f -> EditDialog.create(req, unified, status, () -> IdeaDiff.open(ideaCommand, left, right), f));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		} finally {
			if (dir != null) deleteQuietly(dir);
			lock.unlock();
		}
	}

	@Override
	public String askUser(String question) throws InterruptedException {
		lock.lockInterruptibly();
		try {
			return FxUtil.ask(null, f -> AskDialog.create(question, f));
		} finally {
			lock.unlock();
		}
	}

	private static String unifiedDiff(EditRequest req) {
		List<String> a = req.oldText().lines().toList();
		List<String> b = req.newText().lines().toList();
		Patch<String> patch = DiffUtils.diff(a, b);
		List<String> ud = UnifiedDiffUtils.generateUnifiedDiff("server:" + req.path(), "proposal:" + req.path(), a, patch, 3);
		return ud.isEmpty() ? "(no differences)" : String.join("\n", ud);
	}

	private static String baseName(String path) {
		String n = path.substring(path.lastIndexOf('/') + 1).replaceAll("[\\\\:*?\"<>|]", "_");
		return n.isBlank() ? "file.txt" : n;
	}

	private static void deleteQuietly(Path dir) {
		try (Stream<Path> s = Files.walk(dir)) {
			s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		} catch (IOException ignored) {
			// temporary files - no big deal
		}
	}
}
