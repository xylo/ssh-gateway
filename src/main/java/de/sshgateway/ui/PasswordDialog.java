package de.sshgateway.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.val;

import java.util.concurrent.CompletableFuture;

final class PasswordDialog {
	private PasswordDialog() {
	}

	/**
	 * Result: entered password or null if cancelled.
	 */
	static Stage create(String server, String prompt, String errorMessage, CompletableFuture<String> future) {
		val head = new Label("Sudo Authentication Required [" + server + "]");
		head.getStyleClass().add("heading");
		val p = new Label(prompt);
		p.setWrapText(true);

		val root = new VBox(10, head, p);
		root.setPadding(new Insets(12));

		if (errorMessage != null && !errorMessage.isBlank()) {
			val err = new Label("⚠ " + errorMessage);
			err.setWrapText(true);
			err.getStyleClass().add("warning");
			root.getChildren().add(err);
		}

		val passwordField = new PasswordField();
		passwordField.setPromptText("Sudo password …");
		passwordField.setOnAction(_ -> {
			future.complete(passwordField.getText());
			passwordField.getScene().getWindow().hide();
		});
		root.getChildren().add(passwordField);

		val ok = new Button("Authenticate");
		val cancel = new Button("Cancel");
		FxUtil.finishWith(ok, future, passwordField::getText);
		FxUtil.finishWith(cancel, future, () -> null);
		val buttons = new HBox(8, cancel, ok);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		root.getChildren().add(buttons);

		Platform.runLater(passwordField::requestFocus);

		return FxUtil.stage("SSH-Gateway [" + server + "] – Sudo Password", root, 480, errorMessage != null ? 220 : 180);
	}
}
