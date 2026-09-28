package de.sshgateway.ssh;

public record ExecResult(int exitCode, String stdout, String stderr,
                         boolean stdoutTruncated, boolean stderrTruncated, boolean timedOut) {}
