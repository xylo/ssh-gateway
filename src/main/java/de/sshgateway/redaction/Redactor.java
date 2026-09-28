package de.sshgateway.redaction;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Pure search/replace logic (without UI), with timeout against backtracking. */
public final class Redactor {
    public static final String REPLACEMENT = "*** SENSIBLE INFORMATION ***";

    public record Replaced(String text, int count) {}

    private Redactor() {}

    public static Pattern compile(String text, boolean regex) throws PatternSyntaxException {
        return regex ? Pattern.compile(text, Pattern.MULTILINE) : Pattern.compile(Pattern.quote(text));
    }

    /** Returns [start,end) pairs (empty matches are skipped). */
    public static List<int[]> findAll(Pattern p, String text, int max, long timeoutMs) {
        Matcher m = p.matcher(TimeLimitedCharSequence.of(text, timeoutMs));
        List<int[]> res = new ArrayList<>();
        while (res.size() < max && m.find()) {
            if (m.end() > m.start()) res.add(new int[]{m.start(), m.end()});
        }
        return res;
    }

    public static Replaced replaceAll(Pattern p, String text, long timeoutMs) {
        Matcher m = p.matcher(TimeLimitedCharSequence.of(text, timeoutMs));
        StringBuilder sb = new StringBuilder();
        int last = 0, count = 0;
        while (m.find()) {
            if (m.end() == m.start()) continue;
            sb.append(text, last, m.start()).append(REPLACEMENT);
            last = m.end();
            count++;
        }
        sb.append(text, last, text.length());
        return new Replaced(sb.toString(), count);
    }
}
