package za.co.mrhdigital.audex.scoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/** Runs every shared fixture (../shared/scoring-fixtures.json) through the engine; the TS twin runs the same file. */
class ScoringEngineFixturesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode load(String path) throws Exception {
        try (InputStream in = ScoringEngineFixturesTest.class.getResourceAsStream(path)) {
            return MAPPER.readTree(in);
        }
    }

    @TestFactory
    Stream<DynamicTest> sharedFixtures() throws Exception {
        TemplateCatalog catalog = new TemplateCatalog(MAPPER);
        JsonNode fixtures = load("/scoring-fixtures.json");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode fx : fixtures) {
            tests.add(DynamicTest.dynamicTest(fx.get("name").asText(), () -> {
                Result base = Result.valueOf(fx.get("default").asText());
                Map<String, Result> answers = new HashMap<>();
                for (ScoredItem item : catalog.toScoredItems(Map.of())) answers.put(item.key(), base);
                fx.get("overrides").fields().forEachRemaining(e -> {
                    if (e.getValue().isNull()) answers.remove(e.getKey());
                    else answers.put(e.getKey(), Result.valueOf(e.getValue().asText()));
                });
                RuleSet rules = catalog.ruleSet(fx.get("ruleSet").asText());
                ScoreResult actual = ScoringEngine.evaluate(catalog.toScoredItems(answers), rules);

                JsonNode exp = fx.get("expected");
                assertEquals(exp.get("status").asText(), actual.status().name(), "status");
                assertEquals(exp.get("earnedHalf").asInt(), actual.earnedHalf(), "earnedHalf");
                assertEquals(exp.get("possibleHalf").asInt(), actual.possibleHalf(), "possibleHalf");
                assertEquals(exp.get("criticalFailures").asInt(), actual.criticalFailures(), "criticalFailures");
                assertEquals(strings(exp.get("triggers")), actual.triggers(), "triggers");
                assertEquals(strings(exp.get("flags")), actual.flags(), "flags");
            }));
        }
        return tests.stream();
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}
