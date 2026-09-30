package de.sshgateway.redaction;

/**
 * CharSequence that throws an exception upon access after a deadline expires (protection against catastrophic backtracking).
 */
final class TimeLimitedCharSequence implements CharSequence {
	private final CharSequence inner;
	private final long deadlineNanos;
	private int calls;

	static TimeLimitedCharSequence of(CharSequence inner, long timeoutMs) {
		return new TimeLimitedCharSequence(inner, System.nanoTime() + timeoutMs * 1_000_000L);
	}

	private TimeLimitedCharSequence(CharSequence inner, long deadlineNanos) {
		this.inner = inner;
		this.deadlineNanos = deadlineNanos;
	}

	@Override
	public int length() {
		return inner.length();
	}

	@Override
	public char charAt(int index) {
		if ((++calls & 0x3FF) == 0 && System.nanoTime() > deadlineNanos) throw new RegexTimeoutException();
		return inner.charAt(index);
	}

	@Override
	public CharSequence subSequence(int start, int end) {
		return new TimeLimitedCharSequence(inner.subSequence(start, end), deadlineNanos);
	}

	@Override
	public String toString() {
		return inner.toString();
	}
}
