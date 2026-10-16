package de.cxp.ocs.smartsuggest.util;

import de.cxp.ocs.smartsuggest.spi.SuggestData;
import de.cxp.ocs.smartsuggest.spi.SuggestDataProvider;

/**
 * Registered as service in the test resources. Fails on instantiation, which
 * must not affect the other data providers.
 */
public class FailingDataProvider implements SuggestDataProvider {

	public FailingDataProvider() {
		throw new IllegalStateException("provider cannot be instantiated");
	}

	@Override
	public boolean hasData(String indexName) {
		return false;
	}

	@Override
	public long getLastDataModTime(String indexName) {
		return 0;
	}

	@Override
	public SuggestData loadData(String indexName) {
		return null;
	}
}
