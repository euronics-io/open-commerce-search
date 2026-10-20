package de.cxp.ocs.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.Version;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.cxp.ocs.indexer.model.IndexableItem;

/**
 * Dynamic mappings of numeric fields must not depend on the first value that
 * reaches a shard: with several primary shards every shard of one bulk request
 * proposes its own mapping (long for 2, float for 2.5) for the same new field.
 */
@SuppressWarnings("deprecation")
public class ElasticsearchMultiShardIndexingTest {

	private static final int DOC_COUNT = 100;

	private static ElasticsearchContainer	container;
	private static RestHighLevelClient		client;
	private static ElasticsearchIndexClient	indexClient;

	@BeforeAll
	public static void startElasticsearch() throws IOException {
		container = ElasticsearchContainerUtil.spinUpEs(Optional.ofNullable(System.getenv("ES_CONTAINER_VERSION")).orElse(Version.CURRENT.toString()));
		client = ElasticsearchContainerUtil.initClient(container);
		indexClient = new ElasticsearchIndexClient(client);

		try (InputStream componentTemplate = ElasticsearchMultiShardIndexingTest.class.getResourceAsStream("/elasticsearch/_component_templates/ocs_default.json")) {
			put("/_component_template/ocs_default", new String(componentTemplate.readAllBytes(), StandardCharsets.UTF_8));
		}
		// the default of one shard never shows the problem
		put("/_index_template/ocs_multishard_test",
				"{\"index_patterns\":[\"ocs-multishard-*\"],\"priority\":200,\"composed_of\":[\"ocs_default\"],\"template\":{\"settings\":{\"number_of_shards\":5}}}");
	}

	@AfterAll
	public static void shutdown() {
		if (container != null && container.isRunning()) container.stop();
	}

	@Test
	public void numericFieldsAreMappedConsistentlyAcrossShards() throws Exception {
		String index = "ocs-multishard-1";
		indexClient.createFreshIndex(index);

		List<IndexableItem> items = new ArrayList<>();
		for (int i = 0; i < DOC_COUNT; i++) {
			// alternating integer and fractional values
			Number value = i % 2 == 0 ? (Number) Integer.valueOf(i) : (Number) Double.valueOf(i + 0.5);
			IndexableItem item = new IndexableItem("doc" + i);
			item.getResultData().put("voteAverage", value);
			item.getResultData().put("price", value);
			item.getSortData().put("rating", value);
			item.getScores().put("popularity", value);
			items.add(item);
		}

		BulkResponse response = indexClient.indexRecords(index, items).get();
		long failures = java.util.Arrays.stream(response.getItems()).filter(BulkItemResponse::isFailed).count();
		assertEquals(0, failures, "failed bulk items");

		client.indices().refresh(new org.elasticsearch.action.admin.indices.refresh.RefreshRequest(index), RequestOptions.DEFAULT);
		assertEquals(DOC_COUNT, client.count(new CountRequest(index), RequestOptions.DEFAULT).getCount());

		JsonNode props = new ObjectMapper().readTree(get("/" + index + "/_mapping")).get(index).get("mappings").get("properties");
		assertEquals("double", props.at("/resultData/properties/voteAverage/type").asText());
		assertEquals("double", props.at("/resultData/properties/price/type").asText());
		assertEquals("float", props.at("/sortData/properties/rating/type").asText());
		assertEquals("float", props.at("/scores/properties/popularity/type").asText());
	}

	@Test
	public void mappingConflictsAcrossShardsAreRetried() throws Exception {
		// index without the numeric dynamic templates: conflicts are possible again
		String index = "multishard-without-templates";
		client.indices().create(new CreateIndexRequest(index)
				.settings(org.elasticsearch.common.settings.Settings.builder().put("index.number_of_shards", 5)), RequestOptions.DEFAULT);

		List<IndexableItem> items = new ArrayList<>();
		for (int i = 0; i < DOC_COUNT; i++) {
			IndexableItem item = new IndexableItem("doc" + i);
			item.getResultData().put("voteAverage", i % 2 == 0 ? (Number) Integer.valueOf(i) : (Number) Double.valueOf(i + 0.5));
			items.add(item);
		}

		BulkResponse response = indexClient.indexRecords(index, items).get();
		assertFalse(response.hasFailures(), "failed bulk items after retry");
		assertEquals(DOC_COUNT, response.getItems().length);

		client.indices().refresh(new org.elasticsearch.action.admin.indices.refresh.RefreshRequest(index), RequestOptions.DEFAULT);
		assertEquals(DOC_COUNT, client.count(new CountRequest(index), RequestOptions.DEFAULT).getCount());
	}

	private static void put(String endpoint, String json) throws IOException {
		Request request = new Request("PUT", endpoint);
		request.setEntity(new NStringEntity(json, ContentType.APPLICATION_JSON));
		Response response = client.getLowLevelClient().performRequest(request);
		assertTrue(response.getStatusLine().getStatusCode() < 300);
	}

	private static String get(String endpoint) throws IOException {
		Response response = client.getLowLevelClient().performRequest(new Request("GET", endpoint));
		return new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
	}
}
