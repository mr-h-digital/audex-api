package za.co.mrhdigital.audex.scoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Loads the WPC checklist template and the built-in rule sets from the classpath (shared with the frontend). */
@Component
public class TemplateCatalog {

    public record ItemDef(String key, String sectionCode, boolean highRisk, Severity severity, int weight) {}

    private final Map<String, ItemDef> items = new LinkedHashMap<>();
    private final Map<String, RuleSet> ruleSets = new LinkedHashMap<>();
    private final JsonNode template;

    public TemplateCatalog(ObjectMapper mapper) throws IOException {
        try (InputStream t = getClass().getResourceAsStream("/template.json");
             InputStream r = getClass().getResourceAsStream("/rulesets.json")) {
            template = mapper.readTree(t);
            for (JsonNode section : template.get("sections")) {
                for (JsonNode item : section.get("items")) {
                    items.put(item.get("key").asText(), new ItemDef(
                            item.get("key").asText(), section.get("code").asText(), section.get("highRisk").asBoolean(),
                            Severity.valueOf(item.get("severity").asText()), item.get("weight").asInt()));
                }
            }
            for (JsonNode rs : mapper.readTree(r)) {
                RuleSet rule = mapper.treeToValue(rs, RuleSet.class);
                ruleSets.put(rule.code(), rule);
            }
        }
    }

    public JsonNode template() { return template; }

    public List<RuleSet> ruleSets() { return new ArrayList<>(ruleSets.values()); }

    public RuleSet ruleSet(String code) { return ruleSets.get(code); }

    /** Builds the scorable item list for a set of answers; items with no answer are unanswered. */
    public List<ScoredItem> toScoredItems(Map<String, Result> answers) {
        List<ScoredItem> out = new ArrayList<>();
        for (ItemDef d : items.values()) {
            out.add(new ScoredItem(d.key(), d.sectionCode(), d.highRisk(), d.severity(), d.weight(), answers.get(d.key())));
        }
        return out;
    }

    public boolean knows(String itemKey) { return items.containsKey(itemKey); }
}
