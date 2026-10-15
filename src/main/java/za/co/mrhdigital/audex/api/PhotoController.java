package za.co.mrhdigital.audex.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import za.co.mrhdigital.audex.storage.PhotoStorage;

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

    public PhotoController(JdbcTemplate jdbc, Optional<PhotoStorage> storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    @PutMapping("/audits/{auditId}/photos/{photoId}")
    public ResponseEntity<Void> put(
            @PathVariable String auditId, @PathVariable String photoId,
            @RequestParam(required = false) String itemKey, @RequestParam(required = false, defaultValue = "") String caption,
            @RequestHeader(value = "Content-Type", defaultValue = "image/jpeg") String contentType,
            @RequestBody byte[] bytes) {
        PhotoStorage store = storage.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is not configured"));
        UUID audit = uuid(auditId), photo = uuid(photoId);
        String type = contentType.split(";")[0].trim().toLowerCase();
        if (!TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only JPEG, PNG or WebP photos");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Photo must be under 10 MB");

        Integer found = jdbc.queryForObject("SELECT count(*) FROM audit WHERE id = ?", Integer.class, audit);
        if (found == null || found == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Audit has not been synced yet");
        if (itemKey != null && !itemKey.isBlank()) {
            Integer item = jdbc.queryForObject("SELECT count(*) FROM audit_response WHERE audit_id = ? AND item_key = ?", Integer.class, audit, itemKey);
            if (item == null || item == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown item for this audit: " + itemKey);
        }

        String key = "audits/" + audit + "/" + photo;
        store.put(key, bytes, type);
        jdbc.update("""
                INSERT INTO audit_photo (id, audit_id, item_key, storage_key, caption) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET caption = EXCLUDED.caption
                """, photo, audit, itemKey == null || itemKey.isBlank() ? null : itemKey, key, caption);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/photos/{photoId}")
    public ResponseEntity<byte[]> get(@PathVariable String photoId) {
        PhotoStorage store = storage.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is not configured"));
        List<String> keys = jdbc.queryForList("SELECT storage_key FROM audit_photo WHERE id = ?", String.class, uuid(photoId));
        if (keys.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return store.get(keys.get(0))
                .map(p -> ResponseEntity.ok().contentType(MediaType.parseMediaType(p.contentType())).body(p.bytes()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static UUID uuid(String s) {
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid id"); }
    }
}
