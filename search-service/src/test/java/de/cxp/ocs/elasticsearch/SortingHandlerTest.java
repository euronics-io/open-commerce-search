package de.cxp.ocs.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;
import java.util.List;

import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.junit.jupiter.api.Test;

import de.cxp.ocs.config.Field;
import de.cxp.ocs.config.FieldConfigIndex;
import de.cxp.ocs.config.FieldConfiguration;
import de.cxp.ocs.config.FieldLevel;
import de.cxp.ocs.config.FieldType;
import de.cxp.ocs.config.FieldUsage;
import de.cxp.ocs.elasticsearch.query.sort.SortInstruction;
import de.cxp.ocs.model.result.SortOrder;

class SortingHandlerTest {

	private final Field	variantPrice	= new Field("price").setType(FieldType.NUMBER).setUsage(FieldUsage.SORT).setFieldLevel(FieldLevel.BOTH);
	private final Field	masterRating	= new Field("rating").setType(FieldType.NUMBER).setUsage(FieldUsage.SORT).setFieldLevel(FieldLevel.MASTER);

	private final SortingHandler handler = new SortingHandler(
			new FieldConfigIndex(new FieldConfiguration().addField(variantPrice).addField(masterRating)), Collections.emptyList());

	private SearchSourceBuilder sort(QueryBuilder variantFilter, Field... fields) {
		SearchSourceBuilder ssb = new SearchSourceBuilder();
		List<SortInstruction> sortings = java.util.Arrays.stream(fields).map(f -> new SortInstruction(f, f.getName(), SortOrder.DESC)).toList();
		handler.applySorting(sortings, ssb, variantFilter);
		return ssb;
	}

	@Test
	void sortsOnMasterSortDataWithoutPreferredVariantFilter() {
		FieldSortBuilder sort = (FieldSortBuilder) sort(null, variantPrice).sorts().get(0);
		assertEquals("sortData.price", sort.getFieldName());
		assertNull(sort.getNestedSort());
	}

	@Test
	void sortsOnFilteredVariantsWithPreferredVariantFilter() {
		QueryBuilder filter = QueryBuilders.termQuery("variants.filterData.seller", "s1");
		SearchSourceBuilder ssb = sort(filter, variantPrice, masterRating);
		FieldSortBuilder variantSort = (FieldSortBuilder) ssb.sorts().get(0);
		assertEquals("variants.sortData.price", variantSort.getFieldName());
		assertEquals("variants", variantSort.getNestedSort().getPath());
		assertEquals(filter, variantSort.getNestedSort().getFilter());
		assertEquals(org.elasticsearch.search.sort.SortOrder.DESC, variantSort.order());
		// master-only fields stay untouched
		assertEquals("sortData.rating", ((FieldSortBuilder) ssb.sorts().get(1)).getFieldName());
	}
}
