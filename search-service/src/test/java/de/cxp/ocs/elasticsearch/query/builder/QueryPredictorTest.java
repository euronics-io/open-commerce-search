package de.cxp.ocs.elasticsearch.query.builder;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.search.suggest.SortBy;
import org.elasticsearch.search.suggest.term.TermSuggestion;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import de.cxp.ocs.elasticsearch.model.query.ExtendedQuery;
import de.cxp.ocs.elasticsearch.model.query.MultiTermQuery;
import de.cxp.ocs.elasticsearch.model.term.WeightedTerm;

public class QueryPredictorTest {

	private static Histogram.Bucket bucket(String key, long docCount) {
		Histogram.Bucket bucket = Mockito.mock(Histogram.Bucket.class);
		Mockito.when(bucket.getKeyAsString()).thenReturn(key);
		Mockito.when(bucket.getDocCount()).thenReturn(docCount);
		return bucket;
	}

	/**
	 * Two buckets (each is a combination of matched terms) both contain all
	 * terms of the query, so no spell-corrected query has to be added.
	 */
	@Test
	public void testSpellCorrectionIsSkippedIfSeveralQueriesMatchAllTerms() throws IOException {
		// terms "red", "shoez" and the shingle "red shoez" get the weights 4, 2 and 1.
		// Any combination of two of these weights covers all input terms.
		List<Histogram.Bucket> buckets = List.of(bucket("3.0", 10), bucket("7.0", 20));
		Histogram histogram = Mockito.mock(Histogram.class);
		Mockito.when(histogram.getName()).thenReturn("_score_histogram");
		Mockito.<List<? extends Histogram.Bucket>> when(histogram.getBuckets()).thenReturn(buckets);

		TermSuggestion suggestion = new TermSuggestion("searchData.title", 5, SortBy.SCORE);
		TermSuggestion.Entry entry = new TermSuggestion.Entry(new Text("shoez"), 0, 5);
		entry.addOption(new TermSuggestion.Entry.Option(new Text("shoes"), 30, 0.8f));
		suggestion.addTerm(entry);

		SearchResponse response = Mockito.mock(SearchResponse.class);
		Mockito.when(response.getAggregations()).thenReturn(new Aggregations(List.of(histogram)));
		Mockito.when(response.getSuggest()).thenReturn(new Suggest(new ArrayList<>(List.of(suggestion))));
		RestHighLevelClient client = Mockito.mock(RestHighLevelClient.class);
		Mockito.when(client.search(any(SearchRequest.class), any(RequestOptions.class))).thenReturn(response);

		ExtendedQuery query = new ExtendedQuery(new MultiTermQuery(List.of(new WeightedTerm("red"), new WeightedTerm("shoez"))));
		List<PredictedQuery> predictedQueries = new QueryPredictor(client, "index").getQueryMetaData(query, Map.of("title", 1f));

		assertTrue(predictedQueries.stream().allMatch(PredictedQuery::isContainsAllTerms),
				() -> predictedQueries.stream().map(PredictedQuery::getQueryString).collect(Collectors.toList()).toString());
	}
}
