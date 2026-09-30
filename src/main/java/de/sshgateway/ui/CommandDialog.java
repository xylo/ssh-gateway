package de.sshgateway.ui;

import de.sshgateway.review.ReviewService.CommandDecision;
import de.sshgateway.review.ReviewService.CommandDecision.Action;
import de.sshgateway.review.ReviewService.CommandRequest;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import lombok.val;

import java.util.concurrent.CompletableFuture;

final class CommandDialog {
	private CommandDialog() {
	}

	static Stage create(CommandRequest req, CompletableFuture<CommandDecision> future) {
		val head = new Label("Claude wants to run a command on the server");
		head.getStyleClass().add("heading");
		val root = new VBox(10, head);
		root.setPadding(new Insets(12));

		if (req.description() != null && !req.description().isBlank()) {
			val d = new Label("Reason from Claude: " + req.description());
			d.setWrapText(true);
			root.getChildren().add(d);
		}
		if (!req.riskFlags().isEmpty()) {
			val w = new Label("⚠ Warning: " + String.join("; ", req.riskFlags()));
			w.setWrapText(true);
			w.getStyleClass().add("warning");
			root.getChildren().add(w);
		}

		val cmd = new TextArea(req.command());
		cmd.setEditable(false);
		cmd.setWrapText(true);
		cmd.getStyleClass().add("mono");
		VBox.setVgrow(cmd, Priority.ALWAYS);

		val reason = new TextField();
		reason.setPromptText("Reason for rejection (optional – will be passed to Claude)");

		val run = new Button("Execute");
		var held = new Button("Execute with review");
		held.setTooltip(new Tooltip("Executes the command, but holds back the output until you have reviewed it."));
		val reject = new Button("Reject");

		FxUtil.finishWith(run, future, () -> new CommandDecision(Action.EXECUTE, null));
		FxUtil.finishWith(held, future, () -> new CommandDecision(Action.EXECUTE_HELD, null));
		FxUtil.finishWith(reject, future, () -> new CommandDecision(Action.REJECT, reason.getText()));

		val hint = new Label("\"Execute\" will still apply saved redaction rules to the output.");
		hint.getStyleClass().add("hint");
		val buttons = new HBox(8, reject, held, run);
		buttons.setAlignment(Pos.CENTER_RIGHT);
		root.getChildren().addAll(cmd, reason, hint, buttons);

		return FxUtil.stage("SSH-Gateway – Approve command", root, 720, 420);
	}
}
