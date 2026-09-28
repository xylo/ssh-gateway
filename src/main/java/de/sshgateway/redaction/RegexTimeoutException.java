package de.sshgateway.redaction;

public final class RegexTimeoutException extends RuntimeException {
    public RegexTimeoutException() {
        super("Regex evaluation exceeded the time limit");
    }
}
