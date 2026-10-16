package de.cxp.ocs.elasticsearch;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SettingsProxyTest {

	@Test
	void nonStringConfigValuesAreRead() {
		// config parsed from HOCON delivers numbers and booleans instead of strings
		Map<String, Object> config = new HashMap<>();
		config.put("suggest.index.default.maxFetchSize", 5);
		config.put("suggest.index.test.enable", true);
		config.put("suggest.index.test.deduplicate", true);

		SettingsProxy settings = new SettingsProxy(config);

		assertEquals(5, settings.getMaxFetchSize("test"));
		assertEquals(Optional.of(true), settings.isIndexEnabled("test"));
		assertTrue(settings.getIsDeduplicationEnabled("test"));
	}

	@Test
	void closeReleasesTheSharedClient() throws Exception {
		SettingsProxy settings = new SettingsProxy(Map.of("elasticsearch.hosts", "localhost:1"));

		@SuppressWarnings("deprecation")
		org.elasticsearch.client.RestHighLevelClient client = settings.getClient();
		assertSame(client, settings.getClient());
		assertTrue(client.getLowLevelClient().isRunning());

		settings.close();

		assertFalse(client.getLowLevelClient().isRunning());
	}
}
