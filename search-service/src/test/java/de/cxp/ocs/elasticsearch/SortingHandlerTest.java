package de.cxp.ocs.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.junit.jupiter.api.Test;

import de.cxp.ocs.config.Field;
import de.cxp.ocs.config.FieldConfigIndex;
import de.cxp.ocs.config.FieldConfiguration;
import de.cxp.ocs.config.FieldLevel;
import de.cxp.ocs.config.FieldUsage;
import de.cxp.ocs.elasticsearch.query.sort.SortInstruction;
import de.cxp.ocs.model.result.SortOrder;

public class SortingHandlerTest {

	private final SortingHandler underTest = new SortingHandler(
			new FieldConfigIndex(new FieldConfiguration().addField(new Field("price").setUsage(FieldUsage.SORT).setFieldLevel(FieldLevel.VARIANT))),
			Collections.emptyList());

	// an instruction without a field is created for a sort parameter that does not match any known field
	private final List<SortInstruction> unknownFieldSorting = List.of(new SortInstruction(null, "unknown", SortOrder.ASC));

	@Test
	public void testApplySortingWithUnknownField() {
		SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
		underTest.applySorting(unknownFieldSorting, searchSourceBuilder);
		assertTrue(searchSourceBuilder.sorts() == null || searchSourceBuilder.sorts().isEmpty());
	}

	@Test
	public void testVariantSortingsWithUnknownField() {
		assertTrue(underTest.getVariantSortings(unknownFieldSorting).isEmpty());
	}

	@Test
	public void testVariantSortingsWithKnownField() {
		Field price = new Field("price").setUsage(FieldUsage.SORT).setFieldLevel(FieldLevel.VARIANT);
		assertEquals(1, underTest.getVariantSortings(List.of(new SortInstruction(price, "price", SortOrder.DESC))).size());
	}
}
