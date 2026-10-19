package de.cxp.ocs.preprocessor.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.cxp.ocs.config.DataProcessorConfiguration;
import de.cxp.ocs.model.index.Attribute;
import de.cxp.ocs.model.index.Category;
import de.cxp.ocs.model.index.Document;

class RankingRulesDataProcessorTest {

	private static final String ACC = "{\"source\":\"category\",\"patterns\":[\"accessories\",\"accessoire\"],\"factor\":0.3}";

	private static RankingRulesDataProcessor processor(String... idJson) {
		Map<String, String> conf = new HashMap<>();
		for (int i = 0; i < idJson.length; i += 2) {
			conf.put("rule." + idJson[i], idJson[i + 1]);
		}
		var p = new RankingRulesDataProcessor();
		p.initialize(null, conf);
		return p;
	}

	private static double rank(Document doc, String... idJson) {
		processor(idJson).process(doc, true);
		return (Double) doc.getData().get("rankFactor");
	}

	@SuppressWarnings("deprecation")
	private static Document withCat(String... names) {
		Document d = new Document("1");
		for (String n : names) {
			d.addCategory(new Category("1", "Phones"), new Category("2", n));
		}
		return d;
	}

	@Test
	void categoryRuleMatchesAnyCategoryName() {
		for (String n : List.of("Accessories", "Phone-ACCESSORIES-parts", "Accessoires")) {
			assertEquals(0.3, rank(withCat(n), "acc", ACC), n);
		}
		assertEquals(1.0, rank(withCat("Phones"), "acc", ACC));
		assertEquals(1.0, rank(new Document("1"), "acc", ACC));
	}

	@Test
	void textSourcesFoldExactRegexAndKeywords() {
		var doc = new Document("1").set("productGroup", "Protective/Design Cover").set("title", "Clear Case für Phone").set("brand", "Fancy").set("keywords", "2020", "Müll");
		assertEquals(0.3, rank(doc, "g", "{\"source\":\"productGroup\",\"patterns\":[\"exact:protective/design cover\"],\"factor\":0.3}"));
		assertEquals(1.0, rank(doc, "g", "{\"source\":\"productGroup\",\"patterns\":[\"exact:Protective\"],\"factor\":0.3}"));
		assertEquals(0.5, rank(doc, "t", "{\"source\":\"title\",\"patterns\":[\"regex:^clear\\\\s+case\"],\"factor\":0.5}"));
		assertEquals(0.5, rank(doc, "t", "{\"source\":\"title\",\"patterns\":[\"fuer\"],\"factor\":0.5}"), "umlaut folding");
		assertEquals(1.2, rank(doc, "b", "{\"source\":\"brand\",\"patterns\":[\"FANCY\"],\"factor\":1.2}"));
		assertEquals(0.8, rank(doc, "k", "{\"source\":\"keywords\",\"patterns\":[\"muell\"],\"factor\":0.8}"));
	}

	@Test
	void numericOperatorsAndMissingValues() {
		Document doc = new Document("1").set("salesRank", 500).set("isTopSeller", 1).set("isNew", "0");
		String[][] cases = { { "<=", "500", "y" }, { "<=", "499", "n" }, { ">=", "500", "y" }, { ">", "500", "n" }, { "<", "501", "y" }, { "==", "500", "y" } };
		for (String[] c : cases) {
			double f = rank(doc, "r", "{\"source\":\"field\",\"field\":\"salesRank\",\"op\":\"" + c[0] + "\",\"value\":" + c[1] + ",\"factor\":1.5}");
			assertEquals(c[2].equals("y") ? 1.5 : 1.0, f, c[0] + c[1]);
		}
		assertEquals(1.3, rank(doc, "t", "{\"source\":\"field\",\"field\":\"isTopSeller\",\"op\":\"==\",\"value\":1,\"factor\":1.3}"));
		assertEquals(1.0, rank(doc, "n", "{\"source\":\"field\",\"field\":\"isNew\",\"op\":\"==\",\"value\":1,\"factor\":1.1}"));
		assertEquals(1.0, rank(doc, "m", "{\"source\":\"field\",\"field\":\"nope\",\"op\":\"<=\",\"value\":1000,\"factor\":2}"));
	}

	@Test
	void rulesMultiplyDisabledIgnoredAndClamped() {
		var doc = withCat("Accessories").set("isTopSeller", 1);
		String top = "{\"source\":\"field\",\"field\":\"isTopSeller\",\"op\":\"==\",\"value\":1,\"factor\":1.3}";
		assertEquals(0.3 * 1.3, rank(doc, "a", ACC, "t", top), 1e-9);
		assertEquals(1.3, rank(doc, "a", ACC.replace("}", ",\"enabled\":false}"), "t", top), 1e-9);
		assertEquals(0.05, rank(doc, "a", ACC.replace("0.3", "0.01")), 1e-9);
		assertEquals(5.0, rank(doc, "t", top.replace("1.3", "9")), 1e-9);
	}

	@Test
	void customDestinationAndClampRange() {
		var p = new RankingRulesDataProcessor();
		p.initialize(null, Map.of("destination", "boost", "min", "0.5", "max", "2", "rule.a", ACC.replace("0.3", "0.1"), "rule.b", ACC.replace("0.3", "9")));
		var doc = withCat("Accessories");
		p.process(doc, true);
		assertEquals(0.9, (Double) doc.getData().get("boost"), 1e-9, "0.1 * 9 is within the range");

		p.initialize(null, Map.of("destination", "boost", "min", "0.5", "max", "2", "rule.a", ACC.replace("0.3", "0.1")));
		doc = withCat("Accessories");
		p.process(doc, true);
		assertEquals(0.5, (Double) doc.getData().get("boost"), 1e-9);
	}

	@Test
	void requiresOnlyCountsWhenRequiredRuleMatched() {
		String undo = "{\"source\":\"productGroup\",\"patterns\":[\"exact:In-Ear\"],\"factor\":3,\"requires\":\"acc\"}";
		assertEquals(0.9, rank(withCat("Accessories").set("productGroup", "In-Ear"), "acc", ACC, "undo", undo), 1e-9);
		assertEquals(1.0, rank(withCat("Phones").set("productGroup", "In-Ear"), "acc", ACC, "undo", undo), 1e-9);
	}

	@Test
	void invalidRulesAreIgnoredAndLogged() {
		Logger logger = (Logger) LoggerFactory.getLogger(RankingRulesDataProcessor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			assertEquals(1.0, rank(withCat("Accessories"), "x", "{\"source\":\"bogus\",\"patterns\":[\"a\"],\"factor\":0.3}", "y", "not json", "z",
					"{\"source\":\"title\",\"patterns\":[\"\"],\"factor\":0.3}"));
			assertEquals(3, appender.list.stream().filter(e -> e.getLevel().toString().equals("ERROR")).count());
			assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("'x'")));
		}
		finally {
			logger.detachAppender(appender);
		}
		// a valid rule next to invalid ones still works
		assertEquals(0.3, rank(withCat("Accessories"), "x", "not json", "acc", ACC));
	}

	private static String attrRule(String name, String op, String value, String factor) {
		return "{\"source\":\"attribute\",\"field\":\"" + name + "\",\"op\":\"" + op + "\",\"value\":" + value + ",\"factor\":" + factor + "}";
	}

	@Test
	void attributeRulesCompareNumbersAndNeverMatchMissingOrNonNumeric() {
		Document doc = new Document("1").addAttribute(Attribute.of("Model year", "2026")).addAttribute(Attribute.of("Weight", " 1,5 "))
				.addAttribute(Attribute.of("Color", "red")).addAttribute(Attribute.of("Width", "60 cm")).addAttribute(Attribute.of("Model year", "2025"));
		assertEquals(1.5, rank(doc, "r", attrRule("Model year", "==", "2026", "1.5")));
		assertEquals(1.5, rank(doc, "r", attrRule("Model year", "==", "2025", "1.5")), "any of several same-named attributes");
		assertEquals(1.5, rank(doc, "r", attrRule("Model year", ">=", "2026", "1.5")));
		assertEquals(1.0, rank(doc, "r", attrRule("Model year", ">", "2026", "1.5")));
		assertEquals(1.5, rank(doc, "r", attrRule("Model year", "<=", "2025", "1.5")));
		assertEquals(1.5, rank(doc, "r", attrRule("Weight", "==", "1.5", "1.5")), "decimal comma");
		assertEquals(1.0, rank(doc, "r", attrRule("Color", "<=", "1000", "1.5")));
		assertEquals(1.0, rank(doc, "r", attrRule("Width", "==", "60", "1.5")), "unit text is not a number");
		assertEquals(1.0, rank(doc, "r", attrRule("Missing", "<=", "1000", "1.5")));
		assertEquals(1.0, rank(doc, "r", attrRule("model year", "==", "2026", "1.5")), "name is case-sensitive");
		assertEquals(1.0, rank(new Document("1"), "r", attrRule("Model year", "==", "2026", "1.5")));
		assertEquals(1.0, rank(doc, "r", "{\"source\":\"attribute\",\"field\":\"Weight\",\"op\":\"==\",\"value\":\"x\",\"factor\":2}"), "invalid rule ignored");
		assertEquals(1.5 * 1.2, rank(doc, "a", attrRule("Model year", ">=", "2026", "1.5"), "b", attrRule("Weight", "<=", "2", "1.2")), 1e-9);
	}

	@Test
	void attributeValueMustBePlainNumber() {
		for (String v : new String[] { "", "abc", "2026 Q1", "1e3", "nan", "NaN", "Infinity", "1,5,2", "1." }) {
			Document doc = new Document("1").addAttribute(Attribute.of("A", v));
			assertEquals(1.0, rank(doc, "r", attrRule("A", ">=", "-1000000", "2")), "'" + v + "'");
		}
		for (String v : new String[] { "+2026", "-3", "2026", " 2026,0 " }) {
			Document doc = new Document("1").addAttribute(Attribute.of("A", v));
			assertEquals(2.0, rank(doc, "r", attrRule("A", ">=", "-3", "2")), "'" + v + "'");
		}
	}

	/**
	 * Binds the documented YAML structure (flattened as Spring does it) to the
	 * {@link DataProcessorConfiguration} and runs the processor with the bound map.
	 */
	@Test
	void configurationBindingAsDocumented() {
		Map<String, Object> props = new HashMap<>();
		props.put("data-processor-configuration.processors[0]", "RankingRulesDataProcessor");
		String prefix = "data-processor-configuration.configuration[de.cxp.ocs.preprocessor.impl.RankingRulesDataProcessor]";
		props.put(prefix + ".destination", "rankFactor");
		props.put(prefix + ".max", "5");
		props.put(prefix + ".[rule.accessories]", ACC);
		props.put(prefix + ".[rule.recent-models]", attrRule("Model year", ">=", "2024", "1.2"));
		DataProcessorConfiguration conf = new Binder(new MapConfigurationPropertySource(props))
				.bind("data-processor-configuration", DataProcessorConfiguration.class).get();

		assertEquals(List.of("RankingRulesDataProcessor"), conf.getProcessors());
		var p = new RankingRulesDataProcessor();
		p.initialize(null, conf.getConfiguration().get(RankingRulesDataProcessor.class.getCanonicalName()));
		var doc = withCat("Accessories").addAttribute(Attribute.of("Model year", "2025"));
		p.process(doc, true);
		assertEquals(0.3 * 1.2, (Double) doc.getData().get("rankFactor"), 1e-9);
	}
}
