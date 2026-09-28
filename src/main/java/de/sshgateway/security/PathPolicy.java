package de.sshgateway.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Deny list for remote paths (glob syntax: *, **, ?). */
public final class PathPolicy {
    private final List<Pattern> deny = new ArrayList<>();
    private final List<String> globs;

    public PathPolicy(List<String> globs) {
        this.globs = List.copyOf(globs);
        for (String g : globs) {
            deny.add(globToRegex(g));
            if (g.endsWith("/**")) {
                deny.add(globToRegex(g.substring(0, g.length() - 3))); // the directory itself
            }
        }
    }

    /**
     * Checks whether the specified path is denied.
     * @param path the path
     * @return true if the path is denied, false otherwise
     */
    public boolean isDenied(String path) {
        String n = normalize(path);
        for (Pattern p : deny) if (p.matcher(n).matches()) return true;
        return false;
    }

    /**
     * Short text fragments (e.g., ".env", "id_rsa") to search for in commands.
     *
     * @return the list of literal hints
     */
    public List<String> literalHints() {
        List<String> hints = new ArrayList<>();
        for (String g : globs) {
            String[] segs = g.split("/");
            for (int i = segs.length - 1; i >= 0; i--) {
                String s = segs[i].replace("*", "").replace("?", "");
                if (s.length() >= 3) { if (!hints.contains(s)) hints.add(s); break; }
            }
        }
        return hints;
    }

    /**
     * Normalizes a path by removing all "." and "..".
     * @param path the path
     * @return the normalized path
     */
    static String normalize(String path) {
        boolean abs = path.startsWith("/");
        List<String> st = new ArrayList<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!st.isEmpty() && !st.getLast().equals("..")) st.removeLast();
                else if (!abs) st.add("..");
                continue;
            }
            st.add(seg);
        }
        String j = String.join("/", st);
        return abs ? "/" + j : j;
    }

    /**
     * Converts a glob pattern to a regular expression pattern.
     * @param g the glob pattern
     * @return the regular expression pattern
     */
    static Pattern globToRegex(String g) {
        StringBuilder sb = new StringBuilder();
        int i = 0, n = g.length();
        while (i < n) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < n && g.charAt(i + 1) == '*') {
                    if (i + 2 < n && g.charAt(i + 2) == '/') { sb.append("(?:.*/)?"); i += 3; }
                    else { sb.append(".*"); i += 2; }
                } else { sb.append("[^/]*"); i++; }
            } else if (c == '?') {
                sb.append("[^/]"); i++;
            } else {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) sb.append('\\');
                sb.append(c); i++;
            }
        }
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }
}
