package de.cxp.ocs.preprocessor.impl;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.cxp.ocs.config.FieldConfigAccess;
import de.cxp.ocs.model.index.Category;
import de.cxp.ocs.model.index.Document;
import de.cxp.ocs.spi.indexer.DocumentPreProcessor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * <p>
 * Boosts or demotes products by configurable rules at index time. All rules are
 * evaluated for each document and the product of the factors of all matching
 * rules (clamped to [min, max], 1 if nothing matches) is written into ONE
 * numeric data field, by default <code>rankFactor</code>.
 * </p>
 * <p>
 * That field must be configured with usage <code>Score</code> and can then be
 * consumed by the scoring configuration of the search service, e.g. with a
 * <code>field_value_factor</code> function and <code>missing: 1</code>.
 * A change of the rules is only effective after a re-index.
 * </p>
 * <p>
 * Each rule is configured with one key <code>rule.&lt;id&gt;</code> and a JSON
 * object as value:
 * </p>
 * 
 * <pre>
 * data-processor-configuration:
 *   processors:
 *     - RankingRulesDataProcessor
 *   configuration:
 *     "[de.cxp.ocs.preprocessor.impl.RankingRulesDataProcessor]":
 *       # optional: name of the score field (default: rankFactor)
 *       destination: rankFactor
 *       # optional: the final factor is clamped to this range (defaults: 0.05 and 5)
 *       min: "0.05"
 *       max: "5"
 *       "[rule.accessories]": '{"source":"category","patterns":["accessories","regex:^spare\\s+parts"],"factor":0.3}'
 *       "[rule.best-sellers]": '{"source":"field","field":"salesRank","op":"&lt;=","value":100,"factor":1.3}'
 *       "[rule.recent-models]": '{"source":"attribute","field":"model year","op":"&gt;=","value":2024,"factor":1.2}'
 * </pre>
 * 
 * <p>
 * Rule properties:
 * </p>
 * <ul>
 * <li><code>source</code>: one of <code>category</code> (any name on any level
 * of any category path), <code>productGroup</code>, <code>title</code>,
 * <code>brand</code>, <code>keywords</code> (any element) for text rules, or
 * <code>field</code> / <code>attribute</code> for numeric rules.</li>
 * <li><code>patterns</code> (text rules): a plain pattern is a case-insensitive
 * substring match after folding (lower case, umlauts transliterated, other
 * diacritics removed). The prefix <code>exact:</code> requires the whole
 * (folded) value to be equal, the prefix <code>regex:</code> is a Java regular
 * expression that is searched (find) in the original text, case-insensitive.</li>
 * <li><code>field</code>, <code>op</code>, <code>value</code> (numeric rules):
 * <code>op</code> is one of <code>==</code> <code>&gt;=</code>
 * <code>&lt;=</code> <code>&gt;</code> <code>&lt;</code>. For source
 * <code>field</code> the name of a numeric or boolean data field is expected
 * (true = 1). For source <code>attribute</code> it is the exact, case-sensitive
 * name of a product attribute; the rule matches if ANY attribute of that name
 * has a plain number as value (optional sign, decimal point or comma, no
 * units or surrounding text) that satisfies the comparison. Missing or
 * non-numeric values never match.</li>
 * <li><code>factor</code>: multiplier if the rule matches (default 1).</li>
 * <li><code>requires</code> (optional): id of another rule. The rule only
 * counts if that rule matched too, e.g. to cancel a demotion for exceptions.</li>
 * <li><code>enabled</code> (optional, default true): disabled rules are
 * ignored.</li>
 * </ul>
 * <p>
 * Invalid rules are ignored and an error is logged.
 * </p>
 */
@Slf4j
@NoArgsConstructor
public class RankingRulesDataProcessor implements DocumentPreProcessor {

	public static final String RULE_PREFIX = "rule.";

	private static final Set<String>	TEXT_SOURCES	= Set.of("category", "productGroup", "title", "brand", "keywords");
	private static final Set<String>	OPS				= Set.of("==", ">=", "<=", ">", "<");

	private record Rule(String id, String source, List<Matcher> matchers, String field, String op, double value, double factor, String requires) {}

	private record Matcher(String folded, boolean exact, Pattern regex) {
		boolean test(String text, String foldedText) {
			if (regex != null) return regex.matcher(text).find();
			return exact ? foldedText.equals(folded) : foldedText.contains(folded);
		}
	}

	private final List<Rule>	rules	= new ArrayList<>();
	private String				destination;
	private double				min, max;

	/** lower case, umlauts transliterated (ö -> oe), other diacritics removed */
	public static String fold(String s) {
		String t = s.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
		return Normalizer.normalize(t, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
	}

	@Override
	public void initialize(FieldConfigAccess fieldConfig, Map<String, String> conf) {
		rules.clear();
		destination = conf == null ? "rankFactor" : conf.getOrDefault("destination", "rankFactor");
		min = conf == null ? 0.05 : Double.parseDouble(conf.getOrDefault("min", "0.05"));
		max = conf == null ? 5 : Double.parseDouble(conf.getOrDefault("max", "5"));
		if (conf == null) return;
		ObjectMapper om = new ObjectMapper();
		conf.forEach((k, v) -> {
			if (!k.startsWith(RULE_PREFIX)) return;
			String id = k.substring(RULE_PREFIX.length());
			try {
				JsonNode n = om.readTree(v);
				if (!n.path("enabled").asBoolean(true)) return;
				rules.add(parse(id, n));
			}
			catch (Exception e) {
				log.error("RankingRulesDataProcessor: rule '{}' is invalid and ignored: {}", id, e.toString());
			}
		});
		log.info("RankingRulesDataProcessor: {} active rules -> {}", rules.size(), destination);
	}

	private static Rule parse(String id, JsonNode n) {
		String source = n.path("source").asText();
		double factor = n.path("factor").asDouble(1);
		if (source.equals("field") || source.equals("attribute")) {
			String op = n.path("op").asText();
			if (!OPS.contains(op) || !n.hasNonNull("field") || !n.path("value").isNumber()) throw new IllegalArgumentException(source + " rule needs field, op (== >= <= > <) and numeric value");
			return new Rule(id, source, List.of(), n.get("field").asText(), op, n.get("value").asDouble(), factor, requires(n));
		}
		if (!TEXT_SOURCES.contains(source) || !n.path("patterns").isArray() || n.get("patterns").isEmpty()) throw new IllegalArgumentException("unknown source '" + source + "' or no patterns");
		List<Matcher> ms = new ArrayList<>();
		for (JsonNode p : n.get("patterns")) {
			String s = p.asText();
			if (s.isBlank()) throw new IllegalArgumentException("empty pattern");
			if (s.startsWith("regex:")) ms.add(new Matcher(null, false, Pattern.compile(s.substring(6), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)));
			else if (s.startsWith("exact:")) ms.add(new Matcher(fold(s.substring(6).trim()), true, null));
			else ms.add(new Matcher(fold(s.trim()), false, null));
		}
		return new Rule(id, source, ms, null, null, 0, factor, requires(n));
	}

	private static String requires(JsonNode n) {
		return n.hasNonNull("requires") ? n.get("requires").asText() : null;
	}

	@Override
	public boolean process(Document document, boolean visible) {
		document.set(destination, factor(document));
		return visible;
	}

	/** product of all matching factors, clamped */
	double factor(Document doc) {
		Map<String, Boolean> raw = new HashMap<>();
		for (Rule r : rules) raw.put(r.id(), matches(r, doc));
		double f = 1;
		for (Rule r : rules) {
			if (effective(r, raw, rules.size())) f *= r.factor();
		}
		return Math.max(min, Math.min(max, f));
	}

	/** matches itself and (if it requires another rule) that rule is effective too; depth guard against cycles */
	private boolean effective(Rule r, Map<String, Boolean> raw, int depth) {
		if (!raw.get(r.id())) return false;
		if (r.requires() == null) return true;
		Rule req = rules.stream().filter(x -> x.id().equals(r.requires())).findFirst().orElse(null);
		return req != null && depth > 0 && effective(req, raw, depth - 1);
	}

	private boolean matches(Rule r, Document doc) {
		if (r.source().equals("field")) return compare(number(doc.getData().get(r.field())), r);
		if (r.source().equals("attribute")) {
			return doc.getAttributes() != null && doc.getAttributes().stream().anyMatch(a -> a != null && r.field().equals(a.getName()) && compare(attributeNumber(a.getValue()), r));
		}
		return texts(r.source(), doc).anyMatch(t -> {
			String folded = fold(t);
			return r.matchers().stream().anyMatch(m -> m.test(t, folded));
		});
	}

	private static boolean compare(Double v, Rule r) {
		if (v == null) return false;
		return switch (r.op()) {
			case "==" -> v == r.value();
			case ">=" -> v >= r.value();
			case "<=" -> v <= r.value();
			case ">" -> v > r.value();
			default -> v < r.value();
		};
	}

	/** plain number only (optional sign, decimal point or comma), surrounding whitespace ignored; anything else (units, text, exponent) is null */
	private static Double attributeNumber(String s) {
		if (s == null || !s.trim().matches("[-+]?\\d+([.,]\\d+)?")) return null;
		return Double.valueOf(s.trim().replace(',', '.'));
	}

	private static Double number(Object o) {
		if (o instanceof Number n) return n.doubleValue();
		if (o instanceof Boolean b) return b ? 1.0 : 0.0;
		if (o instanceof String s) {
			try {
				return Double.valueOf(s.trim());
			}
			catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	@SuppressWarnings("deprecation")
	private static Stream<String> texts(String source, Document doc) {
		if (source.equals("category")) {
			Stream<Category[]> paths = doc.getCategories() == null ? Stream.empty() : doc.getCategories().stream();
			Stream<Category[]> dataPaths = doc.getData().values().stream().flatMap(v -> {
				if (v instanceof Category[] p) return Stream.<Category[]>of(p);
				return v instanceof Category[][] pp ? Stream.of(pp) : Stream.<Category[]>empty();
			});
			return Stream.concat(paths, dataPaths).flatMap(Stream::of).filter(c -> c != null && c.getName() != null).map(Category::getName);
		}
		Object v = doc.getData().get(source);
		if (v == null) return Stream.empty();
		if (v instanceof Collection<?> c) return c.stream().filter(Objects::nonNull).map(String::valueOf);
		if (v instanceof Object[] a) return Arrays.stream(a).filter(Objects::nonNull).map(String::valueOf);
		return Stream.of(String.valueOf(v));
	}
}
