package za.co.mrhdigital.audex.billing;

import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import za.co.mrhdigital.audex.storage.PhotoStorage;

@Service
public class PhotoUsageReconciler {
    public record Result(int reconciled, long remaining) {}
    private final JdbcTemplate jdbc;
    private final Optional<PhotoStorage> storage;
    private final EntitlementService entitlements;
    public PhotoUsageReconciler(JdbcTemplate jdbc, Optional<PhotoStorage> storage, EntitlementService entitlements) {
        this.jdbc = jdbc; this.storage = storage; this.entitlements = entitlements;
    }
    @Transactional
    public Result reconcile(UUID admin, UUID owner) {
        entitlements.requireAdmin(admin);
        entitlements.lock(owner);
        PhotoStorage store = storage.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is not configured."));
        var photos = jdbc.queryForList("""
                SELECT p.id, p.storage_key FROM audit_photo p JOIN audit a ON a.id = p.audit_id
                WHERE a.created_by = ? AND p.size_bytes IS NULL ORDER BY p.id LIMIT 10
                """, owner);
        for (var photo : photos) {
            var saved = store.get((String) photo.get("storage_key")).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "A legacy photo is missing from storage. Restore it before reconciling usage."));
            jdbc.update("UPDATE audit_photo SET size_bytes = ? WHERE id = ? AND size_bytes IS NULL", saved.bytes().length, photo.get("id"));
        }
        long remaining = jdbc.queryForObject("""
                SELECT count(*) FROM audit_photo p JOIN audit a ON a.id = p.audit_id
                WHERE a.created_by = ? AND p.size_bytes IS NULL
                """, Long.class, owner);
        jdbc.update("INSERT INTO billing_audit(user_id, action, actor, reason) VALUES (?, 'photo-usage-reconciled', ?, ?)",
                owner, "admin:" + admin, photos.size() + " photo sizes verified against object storage");
        return new Result(photos.size(), remaining);
    }
}
