package de.cxp.ocs.elasticsearch.facets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.cxp.ocs.model.result.Facet;
import de.cxp.ocs.model.result.FacetEntry;

public class TermFacetCreatorMergeTest {

	private Facet facet(long count) {
		Facet f = new Facet("color");
		f.setEntries(new ArrayList<>());
		f.getEntries().add(new FacetEntry("red", null, count, null, false));
		f.absoluteFacetCoverage = count;
		return f;
	}

	@Test
	public void testMergeTakesGreaterCountAndAdjustsCoverage() {
		TermFacetCreator underTest = new TermFacetCreator(Map.of(), null, Locale.ROOT);
		Facet merged = underTest.mergeFacets(facet(10), facet(18)).get();
		assertEquals(18, merged.getEntries().get(0).docCount);
		assertEquals(18, merged.absoluteFacetCoverage);
	}
}
