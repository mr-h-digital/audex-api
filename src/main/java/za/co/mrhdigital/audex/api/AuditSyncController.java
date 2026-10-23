package za.co.mrhdigital.audex.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import za.co.mrhdigital.audex.auth.AuthInterceptor;
import za.co.mrhdigital.audex.auth.AuthUser;
import za.co.mrhdigital.audex.billing.EntitlementService;
import za.co.mrhdigital.audex.scoring.Result;
import za.co.mrhdigital.audex.scoring.RuleSet;
import za.co.mrhdigital.audex.scoring.ScoreResult;
import za.co.mrhdigital.audex.scoring.ScoringEngine;
import za.co.mrhdigital.audex.scoring.Status;
import za.co.mrhdigital.audex.scoring.TemplateCatalog;

/**
 * Receives finalised audits from the phone. The audit id is generated on the phone, so PUT is idempotent: sending the
 * same audit again (after a dropped connection) acknowledges the saved audit instead of creating a duplicate. The server
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
            String auditorRole,
            String finalisedAt) {}

    public record SyncResponse(String id, Status status, double percent) {}

    public record AuditSummary(UUID id, UUID siteId, String siteName, LocalDate date, String ruleSetCode,
                               String auditor, String unitManager, String supervisor, Instant finalisedAt,
                               Status status, double percent) {}
    public record Photo(UUID id, String itemKey, String caption, Instant takenAt) {}
    public record AuditDetail(UUID id, UUID siteId, String siteName, LocalDate date, String ruleSetCode,
                              String auditor, String auditorRole, String unitManager, String supervisor, Instant finalisedAt,
                              Map<String, Result> answers, Map<String, String> naReasons, ScoreResult score,
                              List<Photo> photos) {}

    private static final String TEMPLATE_NAME = "WPC Housekeeping";

    private final JdbcTemplate jdbc;
    private final TemplateCatalog catalog;
    private final ObjectMapper mapper;
    private final SyncAccess access;
    private final EntitlementService entitlements;

    public AuditSyncController(JdbcTemplate jdbc, TemplateCatalog catalog, ObjectMapper mapper, SyncAccess access, EntitlementService entitlements) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.mapper = mapper;
        this.access = access;
        this.entitlements = entitlements;
    }

    @PutMapping("/audits/{id}")
    @Transactional
    public ResponseEntity<SyncResponse> put(@PathVariable String id, @Valid @RequestBody AuditPayload p,
                                            @RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user) throws JsonProcessingException {
        UUID owner = SyncAccess.account(user);
        entitlements.requireWrite(owner);
        if (!id.equals(p.id())) throw bad("Audit id in the URL and body differ");
        UUID auditId = uuid(p.id()), siteId = uuid(p.siteId());
        LocalDate date;
        try { date = LocalDate.parse(p.date()); } catch (DateTimeParseException e) { throw bad("Invalid audit date"); }
        Instant finalised;
        try { finalised = (p.finalisedAt() == null ? Instant.now() : Instant.parse(p.finalisedAt())).truncatedTo(ChronoUnit.MICROS); }
        catch (DateTimeParseException e) { throw bad("Invalid finalisation time"); }
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
        // Serialise first uploads and retries of the same client-generated ID.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "audit:" + auditId);
        List<UUID> owners = jdbc.queryForList("SELECT created_by FROM audit WHERE id = ?", UUID.class, auditId);
        if (!owners.isEmpty()) {
            if (!owner.equals(owners.getFirst())) throw SyncAccess.missing();
            AuditDetail saved = detail(auditId, user);
            boolean same = saved.siteId().equals(siteId) && saved.siteName().equals(p.siteName())
                    && saved.date().equals(date) && saved.ruleSetCode().equals(p.ruleSetCode())
                    && Objects.equals(saved.unitManager(), p.unitManager()) && Objects.equals(saved.supervisor(), p.supervisor())
                    && Objects.equals(saved.auditorRole(), p.auditorRole())
                    && saved.answers().equals(p.answers()) && saved.naReasons().equals(reasons(p))
                    && (p.finalisedAt() == null || Objects.equals(saved.finalisedAt(), finalised));
            if (!same) throw SyncAccess.conflict("Finalised audits are read-only. This upload differs from the saved audit.");
            return ResponseEntity.ok(new SyncResponse(p.id(), saved.score().status(), saved.score().percent()));
        }
        UUID templateId = ensureTemplate();
        if (jdbc.queryForObject("SELECT count(*) FROM site WHERE id = ?", Integer.class, siteId) == 0) {
            entitlements.requireSiteCapacity(owner);
        }
        jdbc.update("INSERT INTO rule_set (code, name, config) VALUES (?, ?, ?::jsonb) ON CONFLICT (code) DO NOTHING",
                rules.code(), rules.name(), mapper.writeValueAsString(rules));
        jdbc.update("""
                INSERT INTO site (id, owner_id, name, unit_manager, supervisor) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, siteId, owner, p.siteName(), p.unitManager(), p.supervisor());
        access.site(siteId, owner, true);

        String auditor = user.displayName();
        jdbc.update("""
                INSERT INTO audit (id, site_id, template_id, rule_set_code, rule_set_snapshot, audit_date, auditor, unit_manager,
                                   supervisor, status, earned_half, possible_half, triggers, flags, finalised_at, created_by,
                                   site_name, score_snapshot, auditor_role)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?::jsonb, ?)
                """, auditId, siteId, templateId, rules.code(), mapper.writeValueAsString(rules), java.sql.Date.valueOf(date), auditor,
                p.unitManager(), p.supervisor(), score.status().name(), score.earnedHalf(), score.possibleHalf(),
                mapper.writeValueAsString(score.triggers()), mapper.writeValueAsString(score.flags()), java.sql.Timestamp.from(finalised), owner,
                p.siteName(), mapper.writeValueAsString(score), p.auditorRole());

        for (Map.Entry<String, Result> e : p.answers().entrySet()) {
            String naReason = e.getValue() == Result.NA ? naReason(p, e.getKey()) : null;
            jdbc.update("""
                    INSERT INTO audit_response (audit_id, item_key, result, na_reason, answered_at) VALUES (?, ?, ?, ?, now())
                    """,
                    auditId, e.getKey(), e.getValue().name(), naReason);
        }
        return ResponseEntity.ok(new SyncResponse(p.id(), score.status(), score.percent()));
    }

    @GetMapping("/audits")
    public SyncRecordsController.Page<AuditSummary> list(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user,
                                                       @RequestParam(defaultValue = "0") int offset,
                                                       @RequestParam(defaultValue = "100") int limit) {
        SyncAccess.page(offset, limit);
        return SyncRecordsController.Page.of(jdbc.query("""
                SELECT * FROM audit WHERE created_by = ? ORDER BY id LIMIT ? OFFSET ?
                """, (rs, row) -> new AuditSummary(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getString("site_name"), rs.getDate("audit_date").toLocalDate(), rs.getString("rule_set_code"),
                rs.getString("auditor"), rs.getString("unit_manager"), rs.getString("supervisor"),
                instant(rs, "finalised_at"), Status.valueOf(rs.getString("status")),
                ScoringEngine.percent(rs.getInt("earned_half"), rs.getInt("possible_half"))),
                SyncAccess.account(user), limit + 1, offset), limit);
    }

    @GetMapping("/audits/{id}")
    public AuditDetail detail(@PathVariable UUID id, @RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user) {
        access.audit(id, SyncAccess.account(user));
        Map<String, Result> answers = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        jdbc.query("SELECT item_key, result, na_reason FROM audit_response WHERE audit_id = ?", rs -> {
            String key = rs.getString("item_key");
            if (rs.getString("result") != null) answers.put(key, Result.valueOf(rs.getString("result")));
            if (rs.getString("na_reason") != null) reasons.put(key, rs.getString("na_reason"));
        }, id);
        List<Photo> photos = jdbc.query("SELECT id, item_key, caption, taken_at FROM audit_photo WHERE audit_id = ? ORDER BY id",
                (rs, row) -> new Photo(rs.getObject("id", UUID.class), rs.getString("item_key"), rs.getString("caption"),
                        instant(rs, "taken_at")), id);
        return jdbc.query("SELECT * FROM audit WHERE id = ? AND created_by = ?", (rs, row) -> {
            ScoreResult score = rs.getString("score_snapshot") == null
                    ? ScoringEngine.evaluate(catalog.toScoredItems(answers), read(rs.getString("rule_set_snapshot"), RuleSet.class))
                    : read(rs.getString("score_snapshot"), ScoreResult.class);
            return new AuditDetail(id, rs.getObject("site_id", UUID.class), rs.getString("site_name"),
                    rs.getDate("audit_date").toLocalDate(), rs.getString("rule_set_code"), rs.getString("auditor"),
                    rs.getString("auditor_role"), rs.getString("unit_manager"), rs.getString("supervisor"), instant(rs, "finalised_at"),
                    answers, reasons, score, photos);
        }, id, user.id()).getFirst();
    }

    private <T> T read(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Invalid stored audit snapshot", e); }
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var timestamp = rs.getTimestamp(name);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static Map<String, String> reasons(AuditPayload p) {
        Map<String, String> reasons = new LinkedHashMap<>();
        p.answers().forEach((key, result) -> { if (result == Result.NA) reasons.put(key, naReason(p, key)); });
        return reasons;
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
