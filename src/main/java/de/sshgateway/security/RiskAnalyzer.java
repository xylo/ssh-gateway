package de.sshgateway.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Heuristic flagging of suspicious commands. Not a security boundary – only a hint in the approval dialog.
 */
public final class RiskAnalyzer {
	private record Rule(Pattern pattern, String label) {
	}

	private final List<Rule> rules = new ArrayList<>();
	private final List<String> pathHints;

	public RiskAnalyzer(List<String> extraPatterns, List<String> pathHints) {
		this.pathHints = pathHints;
		add("\\bsudo\\b", "sudo (elevated privileges)");
		add("\\brm\\s+(-[a-zA-Z]*[rf]|--recursive|--force)", "rm with -r/-f (deletion)");
		add("(curl|wget)[^|;&]*\\|\\s*(sudo\\s+)?(sh|bash|zsh)\\b", "Download piped directly to shell");
		add("\\|\\s*(sudo\\s+)?(sh|bash|zsh)\\b", "Output piped to shell");
		add("\\b(dd|mkfs\\S*|fdisk|parted|shred|wipefs)\\b", "Disk operation");
		add("\\b(shutdown|reboot|halt|poweroff)\\b|\\binit\\s+[06]\\b", "Shutdown/Reboot");
		add("\\b(chmod|chown)\\s+(-\\w*R|--recursive)", "Recursive permission change");
		add(">{1,2}\\s*(?!&|/dev/null)\\S", "Writes to file via redirection");
		add("\\b(eval|base64\\s+-d|xargs)\\b", "eval/base64 -d/xargs (obfuscation possible)");
		add("\\b(nc|ncat|netcat|socat)\\b", "Network tool (nc/socat)");
		add("\\b(scp|rsync|sftp|ssh)\\b", "Additional network connection / file transfer");
		add("\\b(systemctl|service)\\s+(stop|disable|restart|mask)\\b", "Service stopped/restarted");
		add("\\b(iptables|nft|ufw|firewall-cmd)\\b", "Firewall modification");
		for (String e : extraPatterns) {
			rules.add(new Rule(Pattern.compile(e, Pattern.CASE_INSENSITIVE), "Custom rule: " + e));
		}
	}

	private void add(String regex, String label) {
		rules.add(new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), label));
	}

	public List<String> analyze(String command) {
		List<String> flags = new ArrayList<>();
		for (Rule r : rules) if (r.pattern().matcher(command).find()) flags.add(r.label());
		for (String h : pathHints) if (command.contains(h)) flags.add("May reference a denied path: " + h);
		return flags;
	}
}
