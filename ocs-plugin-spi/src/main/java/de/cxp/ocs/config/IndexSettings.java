package de.cxp.ocs.config;

import lombok.Getter;
import lombok.Setter;

@Setter
public class IndexSettings {

	/**
	 * Primary shards of newly created indices. Not set (null) keeps the
	 * cluster/template default. Fixed at index creation, a change needs a re-import.
	 */
	public Integer shardCount;

	public int replicaCount = 1;

	public String refreshInterval = "5s";

	public int minimumDocumentCount = 1;

	public int waitTimeMsForHealthyIndex = 3000;

	@Getter
	public boolean useDefaultConfig;
}
