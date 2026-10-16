package de.cxp.ocs.elasticsearch.query.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryStringQueryBuilder;
import org.junit.jupiter.api.Test;

import de.cxp.ocs.elasticsearch.model.query.ExtendedQuery;
import de.cxp.ocs.elasticsearch.model.query.MultiTermQuery;
import de.cxp.ocs.elasticsearch.model.term.WeightedTerm;

public class VariantQueryFactoryTest {

	private final ExtendedQuery query = new ExtendedQuery(new MultiTermQuery(List.of(new WeightedTerm("red"))));

	@Test
	public void testVariantPrefixIsAddedOnce() {
		VariantQueryFactory underTest = new VariantQueryFactory(Map.of(
				"searchData.title", 1f,
				"variants.searchData.color", 2f));

		BoolQueryBuilder variantQuery = (BoolQueryBuilder) underTest.createMatchAnyTermQuery(query);

		QueryStringQueryBuilder termQuery = (QueryStringQueryBuilder) variantQuery.should().get(0);
		assertEquals(Set.of("variants.searchData.title", "variants.searchData.color"), termQuery.fields().keySet());
		assertEquals(2f, termQuery.fields().get("variants.searchData.color"));
	}
}
