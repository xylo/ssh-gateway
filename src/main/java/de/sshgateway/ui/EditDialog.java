package de.sshgateway.ui;

import de.sshgateway.review.ReviewService.EditDecision;
import de.sshgateway.review.ReviewService.EditRequest;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.val;

import java.util.concurrent.CompletableFuture;

final class EditDialog {
	private EditDialog() {
	}

	static Stage create(EditRequest req, String unifiedDiff, String ideaStatus, Runnable reopenIdea,
											CompletableFuture<EditDecision> future) {
		val head = new Label(req.newFile() ? "Claude wants to create a new file" : "Claude wants to change a file");
		head.getStyleClass().add("heading");
		val path = new Label(req.path());
		path.getStyleClass().add("mono");
		val status = new Label(ideaStatus);
		status.setWrapText(true);
		status.getStyleClass().add("hint");

		val diff = new TextArea(unifiedDiff);
		diff.setEditable(false);
		diff.getStyleClass().add("mono");
		VBox.setVgrow(diff, Priority.ALWAYS);

		val reason = new TextField();
		reason.setPromptText("Reason for rejection (optional – will be passed to Claude)");

		val reopen = new Button("Re-open diff in IntelliJ");
		reopen.setOnAction(e -> reopenIdea.run());
		val reject = new Button("Reject");
		val accept = new Button("Accept");
		FxUtil.finishWith(reject, future, () -> new EditDecision(false, reason.getText()));
		FxUtil.finishWith(accept, future, () -> new EditDecision(true, null));

		var spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);
		val buttons = new HBox(8, reopen, spacer, reject, accept);
		buttons.setAlignment(Pos.CENTER_RIGHT);

		val root = new VBox(8, head, path, status, diff, reason, buttons);
		root.setPadding(new Insets(12));
		return FxUtil.stage("SSH-Gateway – Approve File Change", root, 900, 620);
	}
}