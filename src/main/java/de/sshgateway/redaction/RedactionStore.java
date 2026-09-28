package de.sshgateway.redaction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** The file for "always replace" rules (JSON) and their automatic application. */
public final class RedactionStore {
    public record Result(String text, int count, List<String> warnings) {}

    private static final long RULE_TIMEOUT_MS = 2000;

    private final Path file;
    private final ObjectMapper om = new ObjectMapper();
    private final List<RedactionRule> rules = new CopyOnWriteArrayList<>();
    private final Map<RedactionRule, Pattern> ruleToPattern = new ConcurrentHashMap<>();

    public RedactionStore(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try {
                rules.addAll(om.readValue(file.toFile(), new TypeReference<List<RedactionRule>>() {}));
            } catch (IOException e) {
                System.err.println("Could not read " + file + ": " + e);
            }
        }
    }

    public List<RedactionRule> rules() { return List.copyOf(rules); }

    /** @return false if the rule already existed */
    public synchronized boolean add(RedactionRule rule) throws IOException {
        if (rules.contains(rule)) return false;
        rules.add(rule);
        Files.createDirectories(file.getParent());
        om.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), new ArrayList<>(rules));
        return true;
    }

    /** Applies all stored rules. Non-evaluable rules end up in warnings (Fail-safe: user checks manually then). */
    public Result apply(String text) {
        String cur = text;
        int total = 0;
        List<String> warnings = new ArrayList<>();
        for (RedactionRule r : rules) {
            Pattern p;
            try {
                p = ruleToPattern.computeIfAbsent(r, x -> Redactor.compile(x.pattern(), x.regex()));
            } catch (PatternSyntaxException e) {
                warnings.add("Invalid rule, skipped: " + r.pattern());
                continue;
            }
            try {
                Redactor.Replaced rep = Redactor.replaceAll(p, cur, RULE_TIMEOUT_MS);
                cur = rep.text();
                total += rep.count();
            } catch (RegexTimeoutException e) {
                warnings.add("Rule too slow (timeout), skipped: " + r.pattern());
            }
        }
        return new Result(cur, total, warnings);
    }
}
