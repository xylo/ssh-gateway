package de.sshgateway.ssh;

import de.sshgateway.config.GatewayConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Thin sshj wrapper: commands and SFTP. (Re)connects on demand.
 */
public final class SshConnection implements AutoCloseable {

	/**
	 * A directory entry.
	 */
	public record Entry(String name, boolean directory, long size, long mtimeEpochSeconds) {
	}

	private record Captured(byte[] bytes, boolean truncated) {
		static final Captured EMPTY = new Captured(new byte[0], false);
	}

	private final GatewayConfig cfg;
	private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
	private SSHClient client;

	public SshConnection(GatewayConfig cfg) {
		this.cfg = cfg;
	}

	private synchronized SSHClient client() throws IOException {
		if (client != null && client.isConnected() && client.isAuthenticated()) return client;
		if (client != null) {
			try {
				client.close();
			} catch (IOException ignored) {
			}
		}
		SSHClient c = new SSHClient();
		try {
			if (cfg.knownHostsFile() != null && !cfg.knownHostsFile().isBlank())
				c.loadKnownHosts(new File(cfg.knownHostsFile()));
			else c.loadKnownHosts();
			c.setConnectTimeout(15_000);
			c.connect(cfg.host(), cfg.port());
			c.getConnection().getKeepAlive().setKeepAliveInterval(30);
			authenticate(c);
		} catch (IOException | RuntimeException e) {
			try {
				c.close();
			} catch (IOException ignored) {
			}
			throw e;
		}
		client = c;
		return c;
	}

	private void authenticate(SSHClient c) throws IOException {
		String passphrase = System.getenv("SSHGW_KEY_PASSPHRASE");
		String password = System.getenv("SSHGW_PASSWORD");
		if (cfg.keyFile() != null && !cfg.keyFile().isBlank()) {
			KeyProvider kp = passphrase != null ? c.loadKeys(cfg.keyFile(), passphrase) : c.loadKeys(cfg.keyFile());
			c.authPublickey(cfg.user(), kp);
		} else if (password != null) {
			c.authPassword(cfg.user(), password);
		} else {
			c.authPublickey(cfg.user()); // Default key from ~/.ssh
		}
	}

	// ---------- Commands ----------

	public ExecResult exec(String command, int timeoutSeconds) throws IOException {
		SSHClient c = client();
		try (Session session = c.startSession()) {
			long start = System.nanoTime();
			Session.Command cmd = session.exec(command);
			Future<Captured> out = pool.submit(() -> readCapped(cmd.getInputStream(), cfg.maxOutputBytes()));
			Future<Captured> err = pool.submit(() -> readCapped(cmd.getErrorStream(), cfg.maxOutputBytes()));
			try {
				cmd.join(timeoutSeconds, TimeUnit.SECONDS);
			} catch (ConnectionException ignored) {
				// Timeout or connection closed – judged below based on exit/time
			}
			Integer exit = cmd.getExitStatus();
			boolean timedOut = exit == null && (System.nanoTime() - start) >= timeoutSeconds * 1_000_000_000L - 500_000_000L;
			if (timedOut) {
				try {
					session.close();
				} catch (IOException ignored) {
				}
			}
			Captured o = await(out);
			Captured e = await(err);
			return new ExecResult(exit == null ? -1 : exit,
					new String(o.bytes(), StandardCharsets.UTF_8), new String(e.bytes(), StandardCharsets.UTF_8),
					o.truncated(), e.truncated(), timedOut);
		}
	}

	private static Captured readCapped(InputStream in, long max) throws IOException {
		ByteArrayOutputStream bo = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		boolean truncated = false;
		int n;
		while ((n = in.read(buf)) != -1) {
			long room = max - bo.size();
			if (room > 0) bo.write(buf, 0, (int) Math.min(room, n));
			if (n > room) truncated = true;
		}
		return new Captured(bo.toByteArray(), truncated);
	}

	private static Captured await(Future<Captured> f) {
		try {
			return f.get(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Captured.EMPTY;
		} catch (ExecutionException | TimeoutException e) {
			return Captured.EMPTY;
		}
	}

	// ---------- SFTP ----------

	public String canonicalize(String path) throws IOException {
		try (SFTPClient sftp = client().newSFTPClient()) {
			return sftp.canonicalize(path);
		}
	}

	public boolean exists(String path) throws IOException {
		try (SFTPClient sftp = client().newSFTPClient()) {
			return sftp.statExistence(path) != null;
		}
	}

	/**
	 * @return null if the file does not exist
	 */
	public byte[] readFileIfExists(String path, long maxBytes) throws IOException {
		try (SFTPClient sftp = client().newSFTPClient()) {
			FileAttributes attrs = sftp.statExistence(path);
			if (attrs == null) return null;
			if (attrs.getSize() > maxBytes) {
				throw new IOException("File too large: " + attrs.getSize() + " bytes (limit " + maxBytes + ")");
			}
			try (RemoteFile rf = sftp.open(path); InputStream in = rf.new RemoteFileInputStream()) {
				return in.readAllBytes();
			}
		}
	}

	public byte[] readFile(String path, long maxBytes) throws IOException {
		byte[] data = readFileIfExists(path, maxBytes);
		if (data == null) throw new IOException("No such file: " + path);
		return data;
	}

	/**
	 * Overwrites the file in place (permissions/owner are preserved).
	 */
	public void writeFile(String path, byte[] content) throws IOException {
		try (SFTPClient sftp = client().newSFTPClient();
				 RemoteFile rf = sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC));
				 OutputStream out = rf.new RemoteFileOutputStream(0)) {
			out.write(content);
		}
	}

	public List<Entry> list(String path) throws IOException {
		try (SFTPClient sftp = client().newSFTPClient()) {
			return sftp.ls(path).stream()
					.map((RemoteResourceInfo i) -> new Entry(i.getName(), i.isDirectory(),
							i.getAttributes().getSize(), i.getAttributes().getMtime()))
					.sorted((x, y) -> x.name().compareToIgnoreCase(y.name()))
					.toList();
		}
	}

	@Override
	public synchronized void close() {
		pool.shutdownNow();
		if (client != null) {
			try {
				client.close();
			} catch (IOException ignored) {
			}
		}
	}
}
