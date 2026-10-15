package za.co.mrhdigital.audex.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import za.co.mrhdigital.audex.auth.AuthInterceptor;
import za.co.mrhdigital.audex.auth.AuthUser;
import za.co.mrhdigital.audex.scoring.Result;
import za.co.mrhdigital.audex.scoring.RuleSet;
import za.co.mrhdigital.audex.scoring.ScoreResult;
import za.co.mrhdigital.audex.scoring.ScoringEngine;
import za.co.mrhdigital.audex.scoring.Status;
import za.co.mrhdigital.audex.scoring.TemplateCatalog;

/**
 * Receives finalised audits from the phone. The audit id is generated on the phone, so PUT is idempotent: sending the
 * same audit again (after a dropped connection) updates the same rows instead of creating a duplicate. The server
 * re-scores the answers itself and never trusts the percentage or status the phone calculated.
 */
@RestController
@RequestMapping("/api")
public class AuditSyncController {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuditPayload(
            @NotBlank String id,
            @NotBlank String siteId,
            @NotBlank String siteName,
            @NotBlank String date,
            @NotBlank String ruleSetCode,
            @NotNull Map<String, Result> answers,
            Map<String, String> naReasons,
            String unitManager,
            String supervisor,
            String auditor,
            String finalisedAt) {}

    public record SyncResponse(String id, Status status, double percent) {}

    private static final String AUDITOR = "Chernay Hildebrandt";
    private static final String TEMPLATE_NAME = "WPC Housekeeping";

    private final JdbcTemplate jdbc;
    private final TemplateCatalog catalog;
    private final ObjectMapper mapper;

    public AuditSyncController(JdbcTemplate jdbc, TemplateCatalog catalog, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @PutMapping("/audits/{id}")
    @Transactional
    public ResponseEntity<SyncResponse> put(@PathVariable String id, @Valid @RequestBody AuditPayload p,
                                            @RequestAttribute(value = AuthInterceptor.ATTRIBUTE, required = false) AuthUser user) throws JsonProcessingException {
        if (!id.equals(p.id())) throw bad("Audit id in the URL and body differ");
        UUID auditId = uuid(p.id()), siteId = uuid(p.siteId());
        LocalDate date;
        try { date = LocalDate.parse(p.date()); } catch (Exception e) { throw bad("Invalid audit date"); }
        RuleSet rules = catalog.ruleSet(p.ruleSetCode());
        if (rules == null) throw bad("Unknown rule set: " + p.ruleSetCode());
        for (String key : p.answers().keySet()) if (!catalog.knows(key)) throw bad("Unknown checklist item: " + key);

        ScoreResult score = ScoringEngine.evaluate(catalog.toScoredItems(p.answers()), rules);
        if (score.status() == Status.INCOMPLETE) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Only fully answered audits can be synced");
        }

        for (Map.Entry<String, Result> e : p.answers().entrySet()) {
            if (e.getValue() == Result.NA && naReason(p, e.getKey()) == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "N/A needs a reason: " + e.getKey());
            }
        }
        UUID templateId = ensureTemplate();
        jdbc.update("INSERT INTO rule_set (code, name, config) VALUES (?, ?, ?::jsonb) ON CONFLICT (code) DO NOTHING",
                rules.code(), rules.name(), mapper.writeValueAsString(rules));
        jdbc.update("""
                INSERT INTO site (id, name, unit_manager, supervisor) VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, unit_manager = EXCLUDED.unit_manager,
                    supervisor = EXCLUDED.supervisor, updated_at = now()
                """, siteId, p.siteName(), p.unitManager(), p.supervisor());

        // The signed-in person is the auditor of record; the name typed on the phone is only a fallback.
        String auditor = user != null ? user.displayName() : p.auditor() == null || p.auditor().isBlank() ? AUDITOR : p.auditor().trim();
        UUID createdBy = user == null ? null : user.id();
        Instant finalised = p.finalisedAt() == null ? Instant.now() : Instant.parse(p.finalisedAt());
        jdbc.update("""
                INSERT INTO audit (id, site_id, template_id, rule_set_code, rule_set_snapshot, audit_date, auditor, unit_manager,
                                   supervisor, status, earned_half, possible_half, triggers, flags, finalised_at, created_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                ON CONFLICT (id) DO UPDATE SET unit_manager = EXCLUDED.unit_manager, supervisor = EXCLUDED.supervisor,
                    status = EXCLUDED.status, earned_half = EXCLUDED.earned_half, possible_half = EXCLUDED.possible_half,
                    triggers = EXCLUDED.triggers, flags = EXCLUDED.flags, finalised_at = EXCLUDED.finalised_at, updated_at = now()
                """, auditId, siteId, templateId, rules.code(), mapper.writeValueAsString(rules), java.sql.Date.valueOf(date), auditor,
                p.unitManager(), p.supervisor(), score.status().name(), score.earnedHalf(), score.possibleHalf(),
                mapper.writeValueAsString(score.triggers()), mapper.writeValueAsString(score.flags()), java.sql.Timestamp.from(finalised), createdBy);

        for (Map.Entry<String, Result> e : p.answers().entrySet()) {
            String naReason = e.getValue() == Result.NA ? naReason(p, e.getKey()) : null;
            jdbc.update("""
                    INSERT INTO audit_response (audit_id, item_key, result, na_reason, answered_at) VALUES (?, ?, ?, ?, now())
                    ON CONFLICT (audit_id, item_key) DO UPDATE SET result = EXCLUDED.result, na_reason = EXCLUDED.na_reason
                    """,
                    auditId, e.getKey(), e.getValue().name(), naReason);
        }
        return ResponseEntity.ok(new SyncResponse(p.id(), score.status(), score.percent()));
    }

    private static String naReason(AuditPayload p, String key) {
        String r = p.naReasons() == null ? null : p.naReasons().get(key);
        return r == null || r.isBlank() ? null : r.trim();
    }

    private UUID ensureTemplate() {
        jdbc.update("INSERT INTO checklist_template (id, name, version) VALUES (?, ?, 1) ON CONFLICT (name, version) DO NOTHING",
                UUID.nameUUIDFromBytes((TEMPLATE_NAME + ":1").getBytes()), TEMPLATE_NAME);
        return jdbc.queryForObject("SELECT id FROM checklist_template WHERE name = ? AND version = 1", UUID.class, TEMPLATE_NAME);
    }

    private static UUID uuid(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { throw bad("Invalid id: " + s); }
    }

    private static ResponseStatusException bad(String msg) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg); }
}
