package de.sshgateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class GatewayConfigTest {
	private static final ObjectMapper OM = new ObjectMapper();

	@Test
	void testParseMultiServerMap() throws IOException {
		String json = """
				{
				  "servers": {
				    "web": {
				      "host": "web.example.org",
				      "port": 2222,
				      "user": "deploy"
				    },
				    "db": {
				      "host": "db.example.org",
				      "user": "postgres"
				    }
				  },
				  "defaultServer": "web",
				  "bridgePort": 52000
				}
				""";

		GatewayConfig cfg = OM.readValue(json, GatewayConfig.class);
		assertEquals(2, cfg.servers().size());
		assertTrue(cfg.servers().containsKey("web"));
		assertTrue(cfg.servers().containsKey("db"));
		assertEquals("web", cfg.defaultServer());
		assertEquals(52000, cfg.bridgePort());

		ServerConfig web = cfg.servers().get("web");
		assertEquals("web", web.name());
		assertEquals("web.example.org", web.host());
		assertEquals(2222, web.port());
		assertEquals("deploy", web.user());
		assertEquals(120, web.defaultTimeoutSeconds()); // default

		ServerConfig db = cfg.servers().get("db");
		assertEquals("db", db.name());
		assertEquals("db.example.org", db.host());
		assertEquals(22, db.port()); // default
		assertEquals("postgres", db.user());
	}

	@Test
	void testParseSingleServerInServersMap() throws IOException {
		String json = """
				{
				  "servers": {
				    "main": {
				      "host": "main.example.org",
				      "port": 22,
				      "user": "main-user",
				      "keyFile": "/path/to/key"
				    }
				  },
				  "bridgePort": 51823
				}
				""";

		GatewayConfig cfg = OM.readValue(json, GatewayConfig.class);
		assertEquals(1, cfg.servers().size());
		assertTrue(cfg.servers().containsKey("main"));
		assertEquals("main", cfg.defaultServer()); // Auto-selected when only 1 server

		ServerConfig sc = cfg.servers().get("main");
		assertEquals("main.example.org", sc.host());
		assertEquals("main-user", sc.user());
		assertEquals("/path/to/key", sc.keyFile());
	}

	@Test
	void testMissingServersThrows() {
		String json = """
				{
				  "defaultServer": "main"
				}
				""";

		assertThrows(Exception.class, () -> OM.readValue(json, GatewayConfig.class));
	}
}
