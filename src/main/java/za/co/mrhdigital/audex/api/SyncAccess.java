package za.co.mrhdigital.audex.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import za.co.mrhdigital.audex.auth.AuthUser;

@Component
public class SyncAccess {
    private final JdbcTemplate jdbc;

    public SyncAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static UUID account(AuthUser user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in.");
        return user.id();
    }

    public void site(UUID id, UUID owner, boolean allowArchived) {
        var rows = jdbc.queryForList("SELECT archived FROM site WHERE id = ? AND owner_id = ?", Boolean.class, id, owner);
        if (rows.isEmpty()) throw missing();
        if (!allowArchived && rows.getFirst()) throw conflict("This site is archived.");
    }

    public void audit(UUID id, UUID owner) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM audit WHERE id = ? AND created_by = ?", Integer.class, id, owner);
        if (count == null || count == 0) throw missing();
    }

    public static void page(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use offset >= 0 and limit between 1 and 200.");
        }
    }

    public static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Record not found.");
    }

    public static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
