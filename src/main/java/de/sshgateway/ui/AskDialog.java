package de.sshgateway.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.concurrent.CompletableFuture;

final class AskDialog {
	private AskDialog() {
	}

	/**
	 * Result: answer text or null.
	 */
	static Stage create(String question, CompletableFuture<String> future) {
		Label head = new Label("Claude has a question");
		head.getStyleClass().add("heading");
		Label q = new Label(question);
		q.setWrapText(true);
		TextArea answer = new TextArea();
		answer.setWrapText(true);
		answer.setPromptText("Your answer …");
		VBox.setVgrow(answer, Priority.ALWAYS);
		Button send = new Button("Send answer");
		Button none = new Button("Do not answer");
		FxUtil.finishWith(send, future, answer::getText);
		FxUtil.finishWith(none, future, () -> null);
		HBox buttons = new HBox(8, none, send);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		VBox root = new VBox(10, head, q, answer, buttons);
		root.setPadding(new Insets(12));
		return FxUtil.stage("SSH-Gateway – Question", root, 560, 360);
	}
}
