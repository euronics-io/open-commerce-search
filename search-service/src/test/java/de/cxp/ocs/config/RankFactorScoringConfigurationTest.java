package de.cxp.ocs.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Checks that the scoring configuration documented for consuming the score
 * field of the RankingRulesDataProcessor (docs/indexer_service.md) can be
 * bound.
 */
class RankFactorScoringConfigurationTest {

	@Test
	void documentedScoringConfigurationCanBeBound() {
		var source = new MapConfigurationPropertySource(Map.of(
				"scoring-configuration.boost-mode", "multiply",
				"scoring-configuration.score-mode", "multiply",
				"scoring-configuration.score-functions[0].type", "field_value_factor",
				"scoring-configuration.score-functions[0].field", "rankFactor",
				"scoring-configuration.score-functions[0].options.missing", "1"));
		ScoringConfiguration conf = new Binder(source).bind("scoring-configuration", ScoringConfiguration.class).get();

		assertEquals(BoostMode.MULTIPLY, conf.getBoostMode());
		assertEquals(ScoreMode.MULTIPLY, conf.getScoreMode());
		var function = conf.getScoreFunctions().get(0);
		assertEquals(ScoreType.FIELD_VALUE_FACTOR, function.getType());
		assertEquals("rankFactor", function.getField());
		assertEquals("1", function.getOptions().get(ScoreOption.MISSING));
	}
}
