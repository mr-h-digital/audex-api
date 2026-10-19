package za.co.mrhdigital.audex.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import za.co.mrhdigital.audex.auth.AuthInterceptor;
import za.co.mrhdigital.audex.auth.AuthUser;

@RestController
@RequestMapping("/api")
public class SyncRecordsController {
    public record Page<T>(List<T> items, boolean hasMore) {
        public static <T> Page<T> of(List<T> rows, int limit) {
            return new Page<>(List.copyOf(rows.subList(0, Math.min(rows.size(), limit))), rows.size() > limit);
        }
    }

    public record Site(@NotNull UUID id, @NotBlank String name, String address, String facilityType,
                       String unitManager, String supervisor, @PositiveOrZero long revision,
                       boolean archived, Instant updatedAt) {}

    public record Training(@NotNull UUID id, @NotNull UUID siteId, @NotNull LocalDate date,
                           @NotBlank String topic, @Min(0) Integer attendees, String notes,
                           @PositiveOrZero long revision, boolean archived, Instant updatedAt) {}

    private final JdbcTemplate jdbc;
    private final SyncAccess access;

    public SyncRecordsController(JdbcTemplate jdbc, SyncAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @GetMapping("/sites")
    public Page<Site> sites(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user,
                           @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        SyncAccess.page(offset, limit);
        return Page.of(jdbc.query("SELECT * FROM site WHERE owner_id = ? ORDER BY id LIMIT ? OFFSET ?",
                SyncRecordsController::siteRow, SyncAccess.account(user), limit + 1, offset), limit);
    }

    @PutMapping("/sites/{id}")
    @Transactional
    public Site putSite(@PathVariable UUID id, @Valid @RequestBody Site p,
                        @RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user) {
        sameId(id, p.id());
        UUID owner = SyncAccess.account(user);
        if (p.revision() == 0) {
            jdbc.update("""
                    INSERT INTO site (id, owner_id, name, address, facility_type, unit_manager, supervisor, archived)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                    """, id, owner, p.name(), p.address(), p.facilityType(), p.unitManager(), p.supervisor(), p.archived());
        }
        var rows = jdbc.query("SELECT * FROM site WHERE id = ? AND owner_id = ? FOR UPDATE",
                SyncRecordsController::siteRow, id, owner);
        if (rows.isEmpty()) throw SyncAccess.missing();
        Site current = rows.getFirst();
        boolean equal = Objects.equals(p.name(), current.name()) && Objects.equals(p.address(), current.address())
                && Objects.equals(p.facilityType(), current.facilityType()) && Objects.equals(p.unitManager(), current.unitManager())
                && Objects.equals(p.supervisor(), current.supervisor()) && p.archived() == current.archived();
        checkRevision(p.revision(), current.revision(), equal);
        if (equal) return current;
        jdbc.update("""
                UPDATE site SET name = ?, address = ?, facility_type = ?, unit_manager = ?, supervisor = ?,
                    archived = ?, revision = revision + 1, updated_at = now() WHERE id = ? AND owner_id = ?
                """, p.name(), p.address(), p.facilityType(), p.unitManager(), p.supervisor(), p.archived(), id, owner);
        return jdbc.query("SELECT * FROM site WHERE id = ? AND owner_id = ?", SyncRecordsController::siteRow, id, owner).getFirst();
    }

    @GetMapping("/training-sessions")
    public Page<Training> training(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user,
                                  @RequestParam(required = false) UUID siteId,
                                  @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "100") int limit) {
        SyncAccess.page(offset, limit);
        UUID owner = SyncAccess.account(user);
        if (siteId != null) access.site(siteId, owner, true);
        return Page.of(jdbc.query("""
                SELECT * FROM training_session WHERE owner_id = ? AND (?::uuid IS NULL OR site_id = ?)
                ORDER BY id LIMIT ? OFFSET ?
                """, SyncRecordsController::trainingRow, owner, siteId, siteId, limit + 1, offset), limit);
    }

    @PutMapping("/training-sessions/{id}")
    @Transactional
    public Training putTraining(@PathVariable UUID id, @Valid @RequestBody Training p,
                                @RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user) {
        sameId(id, p.id());
        UUID owner = SyncAccess.account(user);
        access.site(p.siteId(), owner, true);
        if (p.revision() == 0) {
            jdbc.update("""
                    INSERT INTO training_session (id, owner_id, site_id, session_date, topic, attendees, notes, archived)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
                    """, id, owner, p.siteId(), java.sql.Date.valueOf(p.date()), p.topic(), p.attendees(), p.notes(), p.archived());
        }
        var rows = jdbc.query("SELECT * FROM training_session WHERE id = ? AND owner_id = ? FOR UPDATE",
                SyncRecordsController::trainingRow, id, owner);
        if (rows.isEmpty()) throw SyncAccess.missing();
        Training current = rows.getFirst();
        boolean equal = p.siteId().equals(current.siteId()) && p.date().equals(current.date())
                && p.topic().equals(current.topic()) && Objects.equals(p.attendees(), current.attendees())
                && Objects.equals(p.notes(), current.notes()) && p.archived() == current.archived();
        checkRevision(p.revision(), current.revision(), equal);
        if (equal) return current;
        jdbc.update("""
                UPDATE training_session SET site_id = ?, session_date = ?, topic = ?, attendees = ?, notes = ?,
                    archived = ?, revision = revision + 1, updated_at = now() WHERE id = ? AND owner_id = ?
                """, p.siteId(), java.sql.Date.valueOf(p.date()), p.topic(), p.attendees(), p.notes(), p.archived(), id, owner);
        return jdbc.query("SELECT * FROM training_session WHERE id = ? AND owner_id = ?",
                SyncRecordsController::trainingRow, id, owner).getFirst();
    }

    private static void sameId(UUID id, UUID body) {
        if (!id.equals(body)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL and body IDs differ.");
    }

    private static void checkRevision(long supplied, long current, boolean equal) {
        // Accept an identical retry at the previous revision when the save succeeded but its response was lost.
        if (supplied != current && !(supplied == current - 1 && equal)) {
            throw SyncAccess.conflict("This record changed on another device. Review the server copy before saving.");
        }
    }

    private static Site siteRow(ResultSet rs, int row) throws SQLException {
        return new Site(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("address"),
                rs.getString("facility_type"), rs.getString("unit_manager"), rs.getString("supervisor"),
                rs.getLong("revision"), rs.getBoolean("archived"), rs.getTimestamp("updated_at").toInstant());
    }

    private static Training trainingRow(ResultSet rs, int row) throws SQLException {
        return new Training(rs.getObject("id", UUID.class), rs.getObject("site_id", UUID.class),
                rs.getDate("session_date").toLocalDate(), rs.getString("topic"), rs.getObject("attendees", Integer.class),
                rs.getString("notes"), rs.getLong("revision"), rs.getBoolean("archived"), rs.getTimestamp("updated_at").toInstant());
    }
}
