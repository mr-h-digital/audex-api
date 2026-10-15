package za.co.mrhdigital.audex.api;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import za.co.mrhdigital.audex.scoring.Result;
import za.co.mrhdigital.audex.scoring.RuleSet;
import za.co.mrhdigital.audex.scoring.ScoreResult;
import za.co.mrhdigital.audex.scoring.ScoringEngine;
import za.co.mrhdigital.audex.scoring.TemplateCatalog;

@RestController
@RequestMapping("/api")
public class ScoringController {

    public record EvaluateRequest(@NotBlank String ruleSet, @NotNull Map<String, Result> answers) {}

    private final TemplateCatalog catalog;

    public ScoringController(TemplateCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/template")
    public JsonNode template() {
        return catalog.template();
    }

    @GetMapping("/rulesets")
    public List<RuleSet> ruleSets() {
        return catalog.ruleSets();
    }

    /** Official server-side score for a set of answers (keyed by checklist item key). */
    @PostMapping("/scoring/evaluate")
    public ScoreResult evaluate(@RequestBody EvaluateRequest request) {
        RuleSet rules = catalog.ruleSet(request.ruleSet());
        if (rules == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown rule set: " + request.ruleSet());
        }
        for (String key : request.answers().keySet()) {
            if (!catalog.knows(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown checklist item: " + key);
            }
        }
        return ScoringEngine.evaluate(catalog.toScoredItems(request.answers()), rules);
    }
}
