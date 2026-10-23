package za.co.mrhdigital.audex.billing;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EntitlementService implements ApplicationRunner {
    public record Access(String accountId, String plan, boolean writable, boolean administrator, boolean verified,
                         Instant issuedAt, Instant offlineUntil, Instant accessUntil, int siteLimit,
                         long storageBytes, int activeSites, long usedBytes, boolean usageKnown) {}
    private final JdbcTemplate jdbc;
    private final BillingConfig.Properties config;
    private final Clock clock;
    public EntitlementService(JdbcTemplate jdbc, BillingConfig.Properties config, Clock clock) {
        this.jdbc = jdbc; this.config = config; this.clock = clock;
    }
    public boolean enabled() { return config.enabled(); }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled()) return;
        // A single rollout starts all existing accounts' trials exactly once, not once per sign-in.
        int first = jdbc.update("INSERT INTO billing_rollout(singleton, started_at) VALUES (true, ?) ON CONFLICT DO NOTHING",
                Timestamp.from(clock.instant()));
        if (first == 1) jdbc.update("UPDATE app_user SET trial_started_at = ? WHERE trial_started_at IS NULL",
                Timestamp.from(clock.instant()));
        if (!config.bootstrapAdminId().isBlank()) {
            UUID id;
            try { id = UUID.fromString(config.bootstrapAdminId()); }
            catch (IllegalArgumentException e) { throw new IllegalStateException("Invalid bootstrap administrator account ID.", e); }
            Integer eligible = jdbc.queryForObject("""
                    SELECT count(*) FROM complimentary_candidate c JOIN app_user u ON u.id = c.user_id
                    WHERE c.user_id = ? AND c.slot = 'administrator' AND lower(u.email) = c.original_email
                    AND u.email_verified_at IS NOT NULL AND NOT u.disabled
                    """, Integer.class, id);
            if (eligible == null || eligible != 1) throw new IllegalStateException("Bootstrap administrator must be the verified, pre-rollout candidate account.");
            grant(id, "operator:bootstrap", "Verified existing administrator; operator-approved account ID.");
        }
    }

    public Access access(UUID id) {
        Instant now = clock.instant();
        var user = jdbc.queryForMap("SELECT email_verified_at, trial_started_at FROM app_user WHERE id = ?", id);
        boolean verified = user.get("email_verified_at") != null;
        List<String> grants = jdbc.queryForList("""
                SELECT c.slot FROM complimentary_grant g JOIN complimentary_candidate c ON c.user_id = g.user_id
                WHERE g.user_id = ? AND g.revoked_at IS NULL
                """, String.class, id);
        Plan plan = Plan.TRIAL;
        Instant end = user.get("trial_started_at") == null ? Instant.EPOCH
                : ((Timestamp) user.get("trial_started_at")).toInstant().plus(Duration.ofDays(14));
        if (!grants.isEmpty()) { plan = Plan.COMPLIMENTARY; end = null; }
        else {
            var payments = jdbc.queryForList("""
                    SELECT plan, paid_until FROM billing_payment WHERE user_id = ? AND NOT reversed
                    ORDER BY paid_until DESC LIMIT 1
                    """, id);
            if (!payments.isEmpty() && ((Timestamp) payments.getFirst().get("paid_until")).toInstant().isAfter(end)) {
                plan = Plan.valueOf((String) payments.getFirst().get("plan"));
                end = ((Timestamp) payments.getFirst().get("paid_until")).toInstant();
            }
        }
        int sites = jdbc.queryForObject("SELECT count(*) FROM site WHERE owner_id = ? AND NOT archived", Integer.class, id);
        var usage = jdbc.queryForMap("""
                SELECT coalesce(sum(p.size_bytes), 0) AS bytes, count(*) FILTER (WHERE p.size_bytes IS NULL) AS unknown
                FROM audit_photo p JOIN audit a ON a.id = p.audit_id WHERE a.created_by = ?
                """, id);
        boolean known = ((Number) usage.get("unknown")).longValue() == 0;
        boolean writable = verified && (end == null || now.isBefore(end));
        Instant offline = now.plus(Duration.ofDays(7));
        if (end != null && end.isBefore(offline)) offline = end;
        return new Access(id.toString(), plan.name(), writable, grants.contains("administrator"), verified,
                now, offline, end, plan.siteLimit, plan.storageBytes, sites, ((Number) usage.get("bytes")).longValue(), known);
    }

    /** All resource mutations lock the account first, serialising site and byte limits across concurrent requests. */
    public Access requireWrite(UUID id) {
        if (!enabled()) return null;
        lock(id);
        Access access = access(id);
        if (!access.writable()) throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                access.verified() ? "Your trial or subscription has ended. Existing records remain readable and exportable."
                        : "Verify your email before creating or syncing new work.");
        return access;
    }
    public void lock(UUID id) { jdbc.queryForList("SELECT id FROM app_user WHERE id = ? FOR UPDATE", id); }
    public void requireSiteCapacity(UUID id) {
        Access a = requireWrite(id);
        if (a != null && a.activeSites() >= a.siteLimit()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Your active-site limit has been reached. Archive an unused site or change plan.");
    }
    public void requireStorage(UUID id, long additionalBytes) {
        Access a = requireWrite(id);
        if (a == null || a.plan().equals("COMPLIMENTARY")) return;
        if (!a.usageKnown()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Legacy photo sizes must be reconciled by the administrator before more photos can be uploaded.");
        if (additionalBytes < 0 || additionalBytes > a.storageBytes() - a.usedBytes()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Your photo storage allowance has been reached. Change plan before uploading more photos.");
        }
    }
    public void requireAdmin(UUID id) {
        Access a = enabled() ? access(id) : null;
        if (a == null || !a.administrator() || !a.verified()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Verified administrator access is required.");
    }
    @Transactional
    public void grant(UUID id, String actor, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide an ownership-verification reason (up to 500 characters).");
        Integer eligible = jdbc.queryForObject("""
                SELECT count(*) FROM complimentary_candidate c JOIN app_user u ON u.id = c.user_id
                WHERE c.user_id = ? AND lower(u.email) = c.original_email AND u.email_verified_at IS NOT NULL AND NOT u.disabled
                """, Integer.class, id);
        if (eligible == null || eligible != 1) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a verified pre-rollout candidate can receive complimentary access.");
        int changed = jdbc.update("""
                INSERT INTO complimentary_grant(user_id, granted_by, reason) VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO NOTHING
                """, id, actor, reason.trim());
        if (changed > 0) jdbc.update("INSERT INTO billing_audit(user_id, action, actor, reason) VALUES (?, 'complimentary-granted', ?, ?)", id, actor, reason.trim());
    }
}
