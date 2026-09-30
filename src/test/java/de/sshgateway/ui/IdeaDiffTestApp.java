package de.sshgateway.ui;

import java.nio.file.Path;

public class IdeaDiffTestApp {

	static void main() {
		final String idea = "C:\\Users\\endrullis\\AppData\\Local\\JetBrains\\Toolbox\\scripts\\idea.cmd";
		final Path left = Path.of("config.example.json");
		final Path right = Path.of("config.example.json");
		System.out.println(IdeaDiff.open(idea, left, right));
	}

}