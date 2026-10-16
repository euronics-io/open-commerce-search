package de.cxp.ocs.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.http.HttpHost;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.RestHighLevelClientBuilder;
import org.elasticsearch.xcontent.XContentType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import org.testcontainers.utility.DockerImageName;

import de.cxp.ocs.smartsuggest.spi.SuggestData;
import de.cxp.ocs.smartsuggest.spi.SuggestRecord;

@SuppressWarnings("deprecation")
class ElasticsearchSuggestDataProviderTest {

	private static final String	INDEX	= "ocs-1-suggest-test";

	private static GenericContainer<?>		container;
	private static RestHighLevelClient		client;
	private static String					esVersion;

	@BeforeAll
	static void setUp() throws Exception {
		esVersion = Optional.ofNullable(System.getenv("ES_CONTAINER_VERSION")).orElse("7.17.29");
		container = new GenericContainer<>(DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch").withTag(esVersion))
				.withExposedPorts(9200);
		container.withEnv("discovery.type", "single-node");
		container.withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
		container.withEnv("xpack.security.enabled", "false");
		container.withEnv("xpack.security.http.ssl.enabled", "false");
		container.setWaitStrategy(new HttpWaitStrategy().forPort(9200));
		container.withStartupTimeout(Duration.ofSeconds(120));
		container.start();

		client = new RestHighLevelClientBuilder(RestClient.builder(new HttpHost("localhost", container.getMappedPort(9200))).build())
				.setApiCompatibilityMode(isVersion8OrHigher())
				.build();

		Request createIndex = new Request("PUT", "/" + INDEX);
		createIndex.setJsonEntity("{\"mappings\":{\"properties\":{"
				+ "\"searchData\":{\"properties\":{\"title\":{\"type\":\"text\"}}},"
				+ nestedFacetMapping("termFacetData") + ","
				+ nestedFacetMapping("pathFacetData") + ","
				+ nestedFacetMapping("numberFacetData") + ","
				+ "\"variants\":{\"properties\":{" + nestedFacetMapping("termFacetData") + "," + nestedFacetMapping("numberFacetData") + "}}"
				+ "}}}");
		client.getLowLevelClient().performRequest(createIndex);

		indexDoc("1", "Red Shoe");
		indexDoc("2", "Blue Shoe");
		indexDoc("3", "Red Shoe");
		client.indices().refresh(new RefreshRequest(INDEX), RequestOptions.DEFAULT);
	}

	private static String nestedFacetMapping(String name) {
		return "\"" + name + "\":{\"type\":\"nested\",\"properties\":{\"name\":{\"type\":\"keyword\"},\"value\":{\"type\":\"keyword\"}}}";
	}

	private static void indexDoc(String id, String title) throws Exception {
		client.index(new IndexRequest(INDEX).id(id).source("{\"searchData\":{\"title\":\"" + title + "\"}}", XContentType.JSON), RequestOptions.DEFAULT);
	}

	private static boolean isVersion8OrHigher() {
		return !esVersion.startsWith("7.");
	}

	@AfterAll
	static void tearDown() throws Exception {
		if (client != null) client.close();
		if (container != null) container.stop();
	}

	@Test
	void fetchesTermsFromSearchDataField() throws Exception {
		Map<String, Object> config = new HashMap<>();
		config.put("elasticsearch.hosts", "localhost:" + container.getMappedPort(9200));
		config.put("elasticsearch.useCompatibilityMode", String.valueOf(isVersion8OrHigher()));
		config.put("suggest.index." + INDEX + ".sourceFields", "title");

		ElasticsearchSuggestDataProvider provider = new ElasticsearchSuggestDataProvider();
		provider.configure(config);

		SuggestData data = provider.loadData(INDEX);

		Map<String, Long> counts = StreamSupport.stream(data.getSuggestRecords().spliterator(), false).collect(Collectors.toMap(SuggestRecord::getPrimaryText, SuggestRecord::getWeight));
		assertEquals(Set.of("red shoe", "blue shoe"), counts.keySet());
		assertEquals(2L, counts.get("red shoe"));
		assertEquals(1L, counts.get("blue shoe"));
	}
}
