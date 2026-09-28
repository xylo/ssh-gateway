package de.sshgateway;

import de.sshgateway.config.GatewayConfig;
import de.sshgateway.mcp.GatewayTools;
import de.sshgateway.mcp.McpServer;
import de.sshgateway.redaction.RedactionStore;
import de.sshgateway.review.ReviewService;
import de.sshgateway.security.AuditLog;
import de.sshgateway.security.Backups;
import de.sshgateway.security.PathPolicy;
import de.sshgateway.security.RiskAnalyzer;
import de.sshgateway.ssh.SshConnection;
import de.sshgateway.ui.FxReviewService;
import javafx.application.Platform;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() {}

    static void main(String[] args) {
        // stdout belongs exclusively to the MCP protocol. Everything else (including accidental println calls) goes to stderr.
        PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        System.setOut(System.err);

        try {
            Path home = GatewayConfig.homeDir();
            Files.createDirectories(home);
            GatewayConfig cfg = GatewayConfig.load(home);

            CountDownLatch fxReady = new CountDownLatch(1);
            Platform.startup(fxReady::countDown);
            fxReady.await();
            Platform.setImplicitExit(false);

            RedactionStore store = new RedactionStore(home.resolve("redactions.json"));
            PathPolicy policy = new PathPolicy(cfg.denyPaths());
            RiskAnalyzer risk = new RiskAnalyzer(cfg.extraRiskPatterns(), policy.literalHints());
            ReviewService review = new FxReviewService(store, cfg.ideaCommand());
            AuditLog audit = new AuditLog(home.resolve("audit.log"));

            try (SshConnection ssh = new SshConnection(cfg)) {
                GatewayTools tools = new GatewayTools(cfg, ssh, store, review, policy, risk,
                        new Backups(home.resolve("backups")), audit);
                System.err.println("ssh-gateway ready for " + cfg.user() + "@" + cfg.host() + " (configuration: " + home + ")");
                new McpServer(protocolOut, tools.definitions(), GatewayTools.INSTRUCTIONS).run(System.in);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Platform.exit();
            System.exit(1);
        }
        Platform.exit();
        System.exit(0);
    }
}
