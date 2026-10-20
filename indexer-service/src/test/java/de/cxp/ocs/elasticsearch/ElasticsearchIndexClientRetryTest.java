package de.cxp.ocs.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Arrays;

import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.shard.ShardId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import de.cxp.ocs.indexer.model.IndexableItem;

@SuppressWarnings("deprecation")
public class ElasticsearchIndexClientRetryTest {

	private final RestHighLevelClient			restClient	= mock(RestHighLevelClient.class);
	private final ElasticsearchIndexClient	underTest	= new ElasticsearchIndexClient(restClient);

	private static BulkItemResponse ok(int pos, String id) {
		return BulkItemResponse.success(pos, DocWriteRequest.OpType.INDEX, new IndexResponse(new ShardId("idx", "uuid", 0), "_doc", id, 1, 1, 1, true));
	}

	private static BulkItemResponse failed(int pos, String id, String reason) {
		return BulkItemResponse.failure(pos, DocWriteRequest.OpType.INDEX,
				new BulkItemResponse.Failure("idx", "_doc", id, new IllegalArgumentException(reason)));
	}

	private static IndexableItem item(String id) {
		return new IndexableItem(id);
	}

	@Test
	public void onlyMappingConflictsAreRetried() throws IOException {
		BulkResponse first = new BulkResponse(new BulkItemResponse[] {
				ok(0, "a"),
				failed(1, "b", "mapper [resultData.price] cannot be changed from type [float] to [long]"),
				failed(2, "c", "some other failure")
		}, 1);
		BulkResponse second = new BulkResponse(new BulkItemResponse[] { ok(0, "b") }, 1);
		when(restClient.bulk(any(BulkRequest.class), any(RequestOptions.class))).thenReturn(first, second);

		BulkResponse result = underTest.indexRecords("idx", Arrays.asList(item("a"), item("b"), item("c"))).get();

		ArgumentCaptor<BulkRequest> requests = ArgumentCaptor.forClass(BulkRequest.class);
		verify(restClient, times(2)).bulk(requests.capture(), any(RequestOptions.class));
		assertEquals(1, requests.getAllValues().get(1).numberOfActions());
		assertEquals("b", requests.getAllValues().get(1).requests().get(0).id());

		assertEquals(3, result.getItems().length);
		assertFalse(result.getItems()[1].isFailed());
		assertTrue(result.getItems()[2].isFailed());
	}

	@Test
	public void noRetryWithoutMappingConflict() throws IOException {
		BulkResponse first = new BulkResponse(new BulkItemResponse[] { ok(0, "a"), failed(1, "b", "some other failure") }, 1);
		when(restClient.bulk(any(BulkRequest.class), any(RequestOptions.class))).thenReturn(first);

		underTest.indexRecords("idx", Arrays.asList(item("a"), item("b")));

		verify(restClient, times(1)).bulk(any(BulkRequest.class), any(RequestOptions.class));
	}
}
