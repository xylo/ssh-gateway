package de.sshgateway.ui;

import de.sshgateway.redaction.RedactionRule;
import de.sshgateway.redaction.RedactionStore;
import de.sshgateway.redaction.Redactor;
import de.sshgateway.redaction.RegexTimeoutException;
import de.sshgateway.review.ReviewService.OutputDecision;
import de.sshgateway.review.ReviewService.OutputRequest;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Editor for reviewing output/file contents before passing them to Claude:
 * Replace-bar with live highlighting of all matches (optional RegEx), "Always replace" (saves the rule),
 * search bar (Ctrl+F, F3 = next) and free editing of the text.
 */
final class OutputReviewDialog {
    private static final int MAX_MATCHES = 5000;
    private static final long REGEX_TIMEOUT_MS = 2000;
    private static final String ERROR_STYLE = "-fx-control-inner-background: #ffd6d6;";

    private static final byte REPLACE = 1, SEARCH = 2, CURRENT = 4;

    private final OutputRequest req;
    private final RedactionStore store;
    private final CompletableFuture<OutputDecision> future;

    private final CodeArea area = new CodeArea();
    private final TextField replaceField = new TextField();
    private final CheckBox replaceRegex = new CheckBox("RegEx");
    private final Label replaceInfo = new Label();
    private final TextField searchField = new TextField();
    private final Label searchInfo = new Label();
    private final Label status = new Label();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(250));

    private List<int[]> replaceMatches = List.of();
    private List<int[]> searchMatches = List.of();
    private int currentSearch = -1;
    private boolean searchChanged;
    private Stage stage;

    private OutputReviewDialog(OutputRequest req, RedactionStore store, CompletableFuture<OutputDecision> future) {
        this.req = req;
        this.store = store;
        this.future = future;
    }

    static Stage create(OutputRequest req, RedactionStore store, CompletableFuture<OutputDecision> future) {
        return new OutputReviewDialog(req, store, future).build();
    }

    private Stage build() {
        Label title = new Label(req.title());
        title.getStyleClass().add("heading");
        Label info = new Label(req.autoReplacements() > 0
                ? req.autoReplacements() + " replacement(s) were automatically applied by stored rules."
                : "No automatic replacements applied.");
        info.getStyleClass().add("hint");

        VBox top = new VBox(6, title, info);
        if (req.warning() != null) {
            Label warn = new Label("⚠ " + req.warning());
            warn.setWrapText(true);
            warn.getStyleClass().add("warning");
            top.getChildren().add(warn);
        }

        // Editor
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        area.getStyleClass().add("output-area");
        area.replaceText(req.initialText());
        area.getUndoManager().forgetHistory();
        area.moveTo(0);
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(area);

        // Search bar
        searchField.setPromptText("Search (Ctrl+F, F3 = next)");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        Button prev = new Button("▲");
        Button next = new Button("▼");
        prev.setOnAction(e -> step(-1));
        next.setOnAction(e -> step(1));
        searchField.setOnAction(e -> step(1));
        HBox searchBar = new HBox(6, new Label("Search:"), searchField, prev, next, searchInfo);
        searchBar.setAlignment(Pos.CENTER_LEFT);

        // Replace bar
        replaceField.setPromptText("Text to replace");
        HBox.setHgrow(replaceField, Priority.ALWAYS);
        Button replace = new Button("Replace");
        replace.setTooltip(new Tooltip("Replaces all highlighted (yellow) parts with \"" + Redactor.REPLACEMENT + "\""));
        Button always = new Button("Always replace");
        always.setTooltip(new Tooltip("Like 'Replace' but saves the rule in redactions.json for automatic application in the future"));
        replace.setOnAction(e -> replaceAll(false));
        always.setOnAction(e -> replaceAll(true));
        HBox replaceBar = new HBox(6, new Label("Replace:"), replaceField, replaceRegex, replaceInfo, replace, always);
        replaceBar.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("hint");
        top.getChildren().addAll(searchBar, replaceBar, status);

        // Bottom area
        TextField reason = new TextField();
        reason.setPromptText("Reason for discarding (optional)");
        HBox.setHgrow(reason, Priority.ALWAYS);
        Button raw = new Button("Show raw output");
        raw.setOnAction(e -> showRaw());
        Button discard = new Button("Discard");
        Button send = new Button("Send to Claude");
        send.setDefaultButton(false);
        FxUtil.finishWith(discard, future, () -> new OutputDecision(false, null, reason.getText()));
        FxUtil.finishWith(send, future, () -> new OutputDecision(true, area.getText(), null));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.SOMETIMES);
        HBox bottom = new HBox(8, raw, reason, discard, send);
        bottom.setAlignment(Pos.CENTER_RIGHT);

        BorderPane root = new BorderPane(scroll);
        root.setTop(top);
        root.setBottom(bottom);
        BorderPane.setMargin(scroll, new Insets(8, 0, 8, 0));
        root.setPadding(new Insets(10));
        top.setSpacing(6);

        stage = FxUtil.stage("SSH-Gateway – Review Output", root, 1000, 720);

        // Live update (debounced)
        debounce.setOnFinished(e -> refresh());
        area.plainTextChanges().subscribe(c -> debounce.playFromStart());
        replaceField.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        replaceRegex.selectedProperty().addListener((o, a, b) -> debounce.playFromStart());
        searchField.textProperty().addListener((o, a, b) -> { searchChanged = true; debounce.playFromStart(); });

        KeyCodeCombination find = new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN);
        stage.getScene().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (find.match(e)) {
                searchField.requestFocus();
                searchField.selectAll();
                e.consume();
            } else if (e.getCode() == KeyCode.F3) {
                step(e.isShiftDown() ? -1 : 1);
                e.consume();
            }
        });
        return stage;
    }

    // ---------- Calculate and highlight matches ----------

    private void refresh() {
        String text = area.getText();

        String rq = replaceField.getText();
        List<int[]> r = findMatches(rq, replaceRegex.isSelected(), false, replaceField, replaceInfo, text);
        replaceMatches = r == null ? List.of() : r;
        if (r != null && !rq.isEmpty()) replaceInfo.setText(countText(r.size()) + " match(es)");

        String sq = searchField.getText();
        List<int[]> s = findMatches(sq, false, true, searchField, searchInfo, text);
        searchMatches = s == null ? List.of() : s;
        if (searchChanged) {
            currentSearch = searchMatches.isEmpty() ? -1 : 0;
            searchChanged = false;
            applyStyles(text.length());
            gotoCurrent();
        } else {
            if (currentSearch >= searchMatches.size()) currentSearch = searchMatches.isEmpty() ? -1 : 0;
            if (currentSearch < 0 && !searchMatches.isEmpty()) currentSearch = 0;
            applyStyles(text.length());
        }
        updateSearchInfo(s != null && !sq.isEmpty());
    }

    /** @return matches, empty list if input is empty, null on error (info label is then set) */
    private List<int[]> findMatches(String q, boolean regex, boolean ignoreCase, TextField field, Label info, String text) {
        field.setStyle("");
        if (q.isEmpty()) {
            info.setText("");
            return List.of();
        }
        try {
            Pattern p = ignoreCase
                    ? Pattern.compile(Pattern.quote(q), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    : Redactor.compile(q, regex);
            return Redactor.findAll(p, text, MAX_MATCHES, REGEX_TIMEOUT_MS);
        } catch (PatternSyntaxException e) {
            field.setStyle(ERROR_STYLE);
            info.setText("Invalid RegEx");
            return null;
        } catch (RegexTimeoutException e) {
            field.setStyle(ERROR_STYLE);
            info.setText("RegEx too slow");
            return null;
        }
    }

    private void updateSearchInfo(boolean valid) {
        if (!valid) { if (searchField.getText().isEmpty()) searchInfo.setText(""); return; }
        searchInfo.setText(searchMatches.isEmpty() ? "0 matches" : (currentSearch + 1) + "/" + countText(searchMatches.size()));
    }

    private static String countText(int n) {
        return n >= MAX_MATCHES ? MAX_MATCHES + "+" : String.valueOf(n);
    }

    private void applyStyles(int length) {
        if (length == 0) return;
        byte[] flags = new byte[length];
        mark(flags, replaceMatches, REPLACE);
        mark(flags, searchMatches, SEARCH);
        if (currentSearch >= 0 && currentSearch < searchMatches.size()) {
            mark(flags, List.of(searchMatches.get(currentSearch)), CURRENT);
        }
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int i = 0;
        while (i < length) {
            byte f = flags[i];
            int j = i + 1;
            while (j < length && flags[j] == f) j++;
            b.add(styleFor(f), j - i);
            i = j;
        }
        StyleSpans<Collection<String>> spans = b.create();
        area.setStyleSpans(0, spans);
    }

    private static void mark(byte[] flags, List<int[]> matches, byte flag) {
        for (int[] m : matches) {
            int end = Math.min(m[1], flags.length);
            for (int k = Math.max(0, m[0]); k < end; k++) flags[k] |= flag;
        }
    }

    private static Collection<String> styleFor(byte f) {
        if (f == 0) return Collections.emptyList();
        List<String> s = new ArrayList<>(3);
        if ((f & REPLACE) != 0) s.add("replace-match");
        if ((f & SEARCH) != 0) s.add("search-match");
        if ((f & CURRENT) != 0) s.add("search-current");
        return s;
    }

    private void step(int dir) {
        if (searchMatches.isEmpty()) return;
        currentSearch = Math.floorMod(currentSearch + dir, searchMatches.size());
        applyStyles(area.getLength());
        gotoCurrent();
        updateSearchInfo(true);
    }

    private void gotoCurrent() {
        if (currentSearch < 0 || currentSearch >= searchMatches.size()) return;
        area.moveTo(searchMatches.get(currentSearch)[0]);
        area.requestFollowCaret();
    }

    // ---------- Replace ----------

    private void replaceAll(boolean remember) {
        String q = replaceField.getText();
        if (q.isEmpty()) {
            status.setText("Please enter text to replace first.");
            return;
        }
        boolean regex = replaceRegex.isSelected();
        Pattern p;
        try {
            p = Redactor.compile(q, regex);
        } catch (PatternSyntaxException e) {
            status.setText("Invalid RegEx – nothing changed.");
            return;
        }
        String msg = "";
        if (remember) {
            try {
                msg = store.add(new RedactionRule(q, regex))
                        ? "Rule saved (" + store.rules().size() + " rules total). "
                        : "Rule was already saved. ";
            } catch (IOException e) {
                status.setText("Rule could not be saved: " + e.getMessage());
                return;
            }
        }
        List<int[]> matches;
        try {
            matches = Redactor.findAll(p, area.getText(), Integer.MAX_VALUE, REGEX_TIMEOUT_MS * 5);
        } catch (RegexTimeoutException e) {
            status.setText(msg + "RegEx too slow – nothing replaced in text.");
            return;
        }
        for (int i = matches.size() - 1; i >= 0; i--) {
            int[] m = matches.get(i);
            area.replaceText(m[0], m[1], Redactor.REPLACEMENT);
        }
        status.setText(msg + matches.size() + " replacement(s) made.");
        replaceField.clear();
    }

    private void showRaw() {
        TextArea ta = new TextArea(req.rawText());
        ta.setEditable(false);
        ta.getStyleClass().add("mono");
        Stage s = FxUtil.stage("Raw output (unchanged, view-only)", new BorderPane(ta), 800, 560);
        s.initOwner(stage);
        s.show();
    }
}
