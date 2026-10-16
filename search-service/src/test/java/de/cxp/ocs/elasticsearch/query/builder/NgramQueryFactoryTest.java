package de.cxp.ocs.elasticsearch.query.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import de.cxp.ocs.config.Field;
import de.cxp.ocs.config.FieldConfigAccess;
import de.cxp.ocs.config.FieldLevel;
import de.cxp.ocs.elasticsearch.model.query.ExtendedQuery;
import de.cxp.ocs.elasticsearch.model.query.MultiTermQuery;
import de.cxp.ocs.elasticsearch.model.term.WeightedTerm;
import de.cxp.ocs.elasticsearch.query.TextMatchQuery;
import org.elasticsearch.index.query.QueryBuilder;

public class NgramQueryFactoryTest {

	private final ExtendedQuery query = new ExtendedQuery(new MultiTermQuery(List.of(new WeightedTerm("red"), new WeightedTerm("shoes"))));

	private NgramQueryFactory initFactory(FieldLevel fieldLevel) {
		FieldConfigAccess fieldConfig = Mockito.mock(FieldConfigAccess.class);
		Mockito.when(fieldConfig.getField("title")).thenReturn(Optional.of(new Field("title").setFieldLevel(fieldLevel)));
		NgramQueryFactory underTest = new NgramQueryFactory();
		underTest.initialize("ngram", Collections.emptyMap(), Map.of("title", 1f), fieldConfig);
		return underTest;
	}

	@Test
	public void testVariantQueryWithoutVariantFieldsDoesNotExpandToAllFields() {
		TextMatchQuery<QueryBuilder> textQuery = initFactory(FieldLevel.MASTER).createQuery(query);

		// a multi-match query without fields would be expanded to all fields of the index,
		// which fails for indices with more fields than the field-expansion limit
		assertInstanceOf(MatchAllQueryBuilder.class, textQuery.getVariantLevelQuery());
		assertTrue(((MultiMatchQueryBuilder) textQuery.getMasterLevelQuery()).fields().containsKey("searchData.title.ngram"));
	}

	@Test
	public void testVariantQueryWithVariantFields() {
		TextMatchQuery<QueryBuilder> textQuery = initFactory(FieldLevel.VARIANT).createQuery(query);

		MultiMatchQueryBuilder variantQuery = assertInstanceOf(MultiMatchQueryBuilder.class, textQuery.getVariantLevelQuery());
		assertEquals(Set.of("variants.searchData.title.ngram"), variantQuery.fields().keySet());
	}
}
