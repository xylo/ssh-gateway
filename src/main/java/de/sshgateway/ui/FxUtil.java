package de.sshgateway.ui;

import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

final class FxUtil {
	private FxUtil() {
	}

	/**
	 * Shows a window (non-blocking on the FX thread) and waits for the result in the calling thread.
	 * If the window is closed without completing the future, defaultValue applies.
	 * If the calling thread is interrupted (cancelled by Claude Code), the window is closed.
	 */
	static <T> T ask(T defaultValue, Function<CompletableFuture<T>, Stage> factory) throws InterruptedException {
		CompletableFuture<T> future = new CompletableFuture<>();
		AtomicReference<Stage> ref = new AtomicReference<>();
		Platform.runLater(() -> {
			try {
				Stage s = factory.apply(future);
				s.setOnHidden(e -> future.complete(defaultValue));
				ref.set(s);
				s.show();
				s.toFront();
				s.requestFocus();
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get();
		} catch (ExecutionException e) {
			throw new IllegalStateException("UI error: " + e.getCause(), e.getCause());
		} finally {
			Platform.runLater(() -> {
				Stage s = ref.get();
				if (s != null) s.close();
			});
		}
	}

	static Stage stage(String title, Parent root, double width, double height) {
		Stage stage = new Stage();
		stage.setTitle(title);
		Scene scene = new Scene(root, width, height);
		scene.getStylesheets().add(FxUtil.class.getResource("/review.css").toExternalForm());
		stage.setScene(scene);
		stage.setAlwaysOnTop(true);
		return stage;
	}

	/**
	 * Button that completes the future with a value and closes the window.
	 */
	static <T> void finishWith(Button button, CompletableFuture<T> future, java.util.function.Supplier<T> value) {
		button.setOnAction(e -> {
			future.complete(value.get());
			button.getScene().getWindow().hide();
		});
	}
}
