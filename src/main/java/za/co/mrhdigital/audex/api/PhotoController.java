package za.co.mrhdigital.audex.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import za.co.mrhdigital.audex.storage.PhotoStorage;
import za.co.mrhdigital.audex.auth.AuthInterceptor;
import za.co.mrhdigital.audex.auth.AuthUser;
import za.co.mrhdigital.audex.billing.EntitlementService;

/**
 * Audit photos. The phone uploads each photo after its audit has synced, so the audit (and the item the photo belongs
 * to) already exist here. PUT is idempotent on the photo id, so a retry after a dropped connection is harmless.
 */
@RestController
@RequestMapping("/api")
public class PhotoController {

    static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final List<String> TYPES = List.of("image/jpeg", "image/png", "image/webp");

    private final JdbcTemplate jdbc;
    private final Optional<PhotoStorage> storage;
    private final SyncAccess access;
    private final EntitlementService entitlements;

    public PhotoController(JdbcTemplate jdbc, Optional<PhotoStorage> storage, SyncAccess access, EntitlementService entitlements) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.access = access;
        this.entitlements = entitlements;
    }

    @PutMapping("/audits/{auditId}/photos/{photoId}")
    @Transactional
    public ResponseEntity<Void> put(
            @PathVariable String auditId, @PathVariable String photoId,
            @RequestParam(required = false) String itemKey, @RequestParam(required = false, defaultValue = "") String caption,
            @RequestParam(required = false) String takenAt,
            @RequestHeader(value = "Content-Type", defaultValue = "image/jpeg") String contentType,
            @RequestBody byte[] bytes, @RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user) {
        UUID audit = uuid(auditId), photo = uuid(photoId);
        entitlements.requireWrite(SyncAccess.account(user));
        access.audit(audit, SyncAccess.account(user));
        PhotoStorage store = storage.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is not configured"));
        String type = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only JPEG, PNG or WebP photos");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Photo must be under 10 MB");
        Instant taken;
        try { taken = (takenAt == null ? Instant.now() : Instant.parse(takenAt)).truncatedTo(ChronoUnit.MICROS); }
        catch (DateTimeParseException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid photo capture time"); }

        if (itemKey != null && !itemKey.isBlank()) {
            Integer item = jdbc.queryForObject("SELECT count(*) FROM audit_response WHERE audit_id = ? AND item_key = ?", Integer.class, audit, itemKey);
            if (item == null || item == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown item for this audit: " + itemKey);
        }

        String item = itemKey == null || itemKey.isBlank() ? null : itemKey;
        String hash = hash(bytes);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "photo:" + photo);
        var existing = jdbc.queryForList("SELECT audit_id, item_key, caption, storage_key, content_hash, taken_at FROM audit_photo WHERE id = ?", photo);
        if (!existing.isEmpty()) {
            var saved = existing.getFirst();
            if (!audit.equals(saved.get("audit_id"))) throw SyncAccess.missing();
            String savedHash = (String) saved.get("content_hash");
            if (savedHash == null) {
                savedHash = hash(store.get((String) saved.get("storage_key"))
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The saved photo is missing from storage.")).bytes());
            }
            if (!hash.equals(savedHash) || !Objects.equals(item, saved.get("item_key")) || !Objects.equals(caption, saved.get("caption"))
                    || (takenAt != null && !taken.equals(((Timestamp) saved.get("taken_at")).toInstant()))) {
                throw SyncAccess.conflict("Uploaded photos are read-only. This upload differs from the saved photo.");
            }
            return ResponseEntity.noContent().build();
        }
        String key = "audits/" + audit + "/" + photo;
        entitlements.requireStorage(SyncAccess.account(user), bytes.length);
        store.put(key, bytes, type);
        jdbc.update("""
                INSERT INTO audit_photo (id, audit_id, item_key, storage_key, caption, content_hash, taken_at, size_bytes) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, photo, audit, item, key, caption, hash, Timestamp.from(taken), bytes.length);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/photos/{photoId}")
    public ResponseEntity<byte[]> get(@PathVariable String photoId, @RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user) {
        List<String> keys = jdbc.queryForList("""
                SELECT p.storage_key FROM audit_photo p JOIN audit a ON a.id = p.audit_id
                WHERE p.id = ? AND a.created_by = ?
                """, String.class, uuid(photoId), SyncAccess.account(user));
        if (keys.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        PhotoStorage store = storage.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is not configured"));
        return store.get(keys.getFirst())
                .map(p -> ResponseEntity.ok().contentType(MediaType.parseMediaType(p.contentType())).body(p.bytes()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    private static UUID uuid(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid id"); }
    }
}
