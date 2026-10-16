package za.co.mrhdigital.audex.auth;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sign-in, sign-out, account recovery and password changes.
 * Rules that matter:
 * - Wrong username and wrong password give the same message, and take about the same time.
 * - "Forgot username" and "forgot password" always answer the same way, so nobody can use them to find out who has an account.
 * - Reset links work once, expire after 30 minutes, and a successful reset signs the account out everywhere.
 * - Repeated failures are slowed down per username and per address.
 */
@Service
public class AuthService {
    public record Me(UUID id, String username, String displayName, String email, String phone, String jobTitle) {}
    public record Session(String token, Instant expiresAt, Me user) {}

    static final Duration SESSION_IDLE = Duration.ofDays(30);
    static final Duration SESSION_ABSOLUTE = Duration.ofDays(90);
    static final Duration RESET_TTL = Duration.ofMinutes(30);
    private static final String BAD_LOGIN = "Username or password is incorrect.";

    private final JdbcTemplate jdbc;
    private final MailSender mail;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String dummyHash = encoder.encode("not-a-real-password");
    private final AttemptLimiter loginLimiter = new AttemptLimiter(5, Duration.ofMinutes(15).toMillis(), System::currentTimeMillis);
    private final AttemptLimiter ipLimiter = new AttemptLimiter(30, Duration.ofMinutes(15).toMillis(), System::currentTimeMillis);
    private final AttemptLimiter recoveryLimiter = new AttemptLimiter(3, Duration.ofHours(1).toMillis(), System::currentTimeMillis);
    private final AttemptLimiter passwordLimiter = new AttemptLimiter(5, Duration.ofMinutes(15).toMillis(), System::currentTimeMillis);
    private final String setupCode;
    private final String appUrl;

    public AuthService(JdbcTemplate jdbc, MailSender mail,
                       @Value("${audex.auth.setup-code:}") String setupCode, @Value("${audex.app-url:http://localhost:5173}") String appUrl) {
        this.jdbc = jdbc; this.mail = mail; this.setupCode = setupCode; this.appUrl = appUrl.replaceAll("/+$", "");
    }

    // ------------------------------------------------------------------ first-time setup

    public boolean setupRequired() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class);
        return n != null && n == 0;
    }

    /** Creates the first account. Only possible while no account exists, and only with the setup code held by the owner. */
    @Transactional
    public Session setup(String code, String username, String email, String displayName, String phone, String jobTitle, String password, String device) {
        if (!setupRequired()) throw err(HttpStatus.CONFLICT, "This app has already been set up.");
        if (setupCode.isBlank()) throw err(HttpStatus.SERVICE_UNAVAILABLE, "Setup is switched off on this server.");
        if (!Tokens.constantTimeEquals(setupCode, code == null ? "" : code.trim())) throw err(HttpStatus.FORBIDDEN, "That setup code is not right.");
        UUID id = createUser(username, email, displayName, phone, jobTitle, password);
        return openSession(id, device);
    }

    // ------------------------------------------------------------------ sign in / out

    public Session login(String username, String password, String device, String ip) {
        String uKey = "u:" + Identity.normaliseUsername(username), ipKey = "ip:" + ip;
        int wait = Math.max(loginLimiter.minutesBlocked(uKey), ipLimiter.minutesBlocked(ipKey));
        if (wait > 0) throw err(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please try again in " + wait + " minute" + (wait == 1 ? "" : "s") + ".");
        List<Row> rows = jdbc.query("SELECT id, password_hash, disabled FROM app_user WHERE lower(username) = ?",
                (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("password_hash"), rs.getBoolean("disabled")),
                Identity.normaliseUsername(username));
        Row u = rows.isEmpty() ? null : rows.getFirst();
        boolean ok = encoder.matches(password == null ? "" : password, u == null ? dummyHash : u.hash) && u != null && !u.disabled;
        if (!ok) {
            loginLimiter.recordFailure(uKey); ipLimiter.recordFailure(ipKey);
            throw err(HttpStatus.UNAUTHORIZED, BAD_LOGIN);
        }
        loginLimiter.reset(uKey);
        return openSession(u.id, device);
    }

    /** Looks up a bearer token. Returns empty for unknown, expired or signed-out tokens. Slides the idle expiry forward. */
    public Optional<AuthUser> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        List<AuthUser> found = jdbc.query("""
                SELECT s.id AS sid, s.last_used_at, s.absolute_expires_at, u.id AS uid, u.username, u.display_name
                FROM auth_session s JOIN app_user u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > now() AND s.absolute_expires_at > now() AND NOT u.disabled
                """, (rs, i) -> {
                    UUID sid = rs.getObject("sid", UUID.class);
                    if (rs.getTimestamp("last_used_at").toInstant().isBefore(Instant.now().minus(Duration.ofHours(1)))) {
                        Instant next = Instant.now().plus(SESSION_IDLE);
                        Instant hard = rs.getTimestamp("absolute_expires_at").toInstant();
                        jdbc.update("UPDATE auth_session SET last_used_at = now(), expires_at = ? WHERE id = ?", Timestamp.from(next.isAfter(hard) ? hard : next), sid);
                    }
                    return new AuthUser(rs.getObject("uid", UUID.class), rs.getString("username"), rs.getString("display_name"), sid);
                }, Tokens.hash(token));
        return found.stream().findFirst();
    }

    public void logout(UUID sessionId) {
        jdbc.update("UPDATE auth_session SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", sessionId);
    }

    public void logoutEverywhere(UUID userId) {
        jdbc.update("UPDATE auth_session SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    // ------------------------------------------------------------------ profile

    public Me me(UUID userId) {
        return jdbc.queryForObject("SELECT id, username, display_name, email, phone, job_title FROM app_user WHERE id = ?", this::toMe, userId);
    }

    /** Name, phone and job title can change freely; changing the email address needs the current password. */
    @Transactional
    public Me updateMe(UUID userId, String displayName, String email, String phone, String jobTitle, String currentPassword) {
        Me now = me(userId);
        if (displayName == null || displayName.isBlank()) throw err(HttpStatus.BAD_REQUEST, "Please enter your name.");
        String newEmail = Identity.normaliseEmail(email);
        if (!newEmail.equalsIgnoreCase(now.email())) {
            Identity.emailProblem(newEmail).ifPresent(m -> { throw err(HttpStatus.BAD_REQUEST, m); });
            requirePassword(userId, currentPassword);
            if (emailTaken(newEmail, userId)) throw err(HttpStatus.CONFLICT, "That email address is already used by another account.");
        }
        String updatedJobTitle = jobTitle == null ? now.jobTitle() : jobTitle.isBlank() ? null : jobTitle.trim();
        jdbc.update("UPDATE app_user SET display_name = ?, email = ?, phone = ?, job_title = ?, updated_at = now() WHERE id = ?",
                displayName.trim(), newEmail, phone == null || phone.isBlank() ? null : phone.trim(), updatedJobTitle, userId);
        return me(userId);
    }

    @Transactional
    public void changePassword(UUID userId, UUID keepSession, String current, String next) {
        requirePassword(userId, current);
        Me m = me(userId);
        PasswordPolicy.problem(next, m.username(), m.email()).ifPresent(p -> { throw err(HttpStatus.BAD_REQUEST, p); });
        jdbc.update("UPDATE app_user SET password_hash = ?, password_changed_at = now(), updated_at = now() WHERE id = ?", encoder.encode(next), userId);
        jdbc.update("UPDATE auth_session SET revoked_at = now() WHERE user_id = ? AND id <> ? AND revoked_at IS NULL", userId, keepSession);
        mail.send(m.email(), "Your AudEx password was changed",
                "Hello " + m.displayName() + ",\n\nYour AudEx password was just changed. If this was you, no action is needed.\n"
                        + "If it was not you, reset your password straight away from the sign-in screen and tell your administrator.\n");
    }

    // ------------------------------------------------------------------ recovery (always answer the same way)

    public void forgotUsername(String email) {
        String e = Identity.normaliseEmail(email);
        if (recoveryLimiter.minutesBlocked("fu:" + e) > 0) return;
        recoveryLimiter.recordFailure("fu:" + e);
        List<Me> users = jdbc.query("SELECT id, username, display_name, email, phone, job_title FROM app_user WHERE lower(email) = ? AND NOT disabled", this::toMe, e);
        for (Me u : users) {
            mail.send(u.email(), "Your AudEx username",
                    "Hello " + u.displayName() + ",\n\nYour AudEx username is: " + u.username() + "\n\n"
                            + "If you did not ask for this, you can ignore this email.\n");
        }
    }

    @Transactional
    public void forgotPassword(String identifier) {
        String id = identifier == null ? "" : identifier.trim().toLowerCase();
        if (recoveryLimiter.minutesBlocked("fp:" + id) > 0) return;
        recoveryLimiter.recordFailure("fp:" + id);
        List<Me> users = jdbc.query("SELECT id, username, display_name, email, phone, job_title FROM app_user WHERE (lower(username) = ? OR lower(email) = ?) AND NOT disabled", this::toMe, id, id);
        if (users.isEmpty()) return;
        Me u = users.getFirst();
        jdbc.update("UPDATE password_reset_token SET used_at = now() WHERE user_id = ? AND used_at IS NULL", u.id());
        String token = Tokens.create();
        jdbc.update("INSERT INTO password_reset_token (id, user_id, token_hash, expires_at) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), u.id(), Tokens.hash(token), Timestamp.from(Instant.now().plus(RESET_TTL)));
        mail.send(u.email(), "Reset your AudEx password",
                "Hello " + u.displayName() + ",\n\nTo choose a new password, open this link within 30 minutes:\n\n"
                        + appUrl + "/reset-password?token=" + token + "\n\n"
                        + "The link works once. If you did not ask for it, ignore this email: your password has not changed.\n");
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        String expired = "This link has expired or was already used. Please ask for a new one.";
        List<ResetRow> rows = jdbc.query("""
                SELECT u.id AS uid, u.username, u.email, u.display_name FROM password_reset_token t JOIN app_user u ON u.id = t.user_id
                WHERE t.token_hash = ? AND t.used_at IS NULL AND t.expires_at > now() AND NOT u.disabled
                """, (rs, i) -> new ResetRow(rs.getObject("uid", UUID.class), rs.getString("username"), rs.getString("email"), rs.getString("display_name")),
                Tokens.hash(token == null ? "" : token));
        if (rows.isEmpty()) throw err(HttpStatus.BAD_REQUEST, expired);
        ResetRow r = rows.getFirst();
        PasswordPolicy.problem(newPassword, r.username(), r.email()).ifPresent(p -> { throw err(HttpStatus.BAD_REQUEST, p); });
        jdbc.update("UPDATE app_user SET password_hash = ?, password_changed_at = now(), updated_at = now() WHERE id = ?", encoder.encode(newPassword), r.id());
        jdbc.update("UPDATE password_reset_token SET used_at = now() WHERE user_id = ? AND used_at IS NULL", r.id());
        logoutEverywhere(r.id());
        loginLimiter.reset("u:" + Identity.normaliseUsername(r.username()));
        mail.send(r.email(), "Your AudEx password was changed",
                "Hello " + r.displayName() + ",\n\nYour AudEx password was just reset. If this was you, no action is needed.\n"
                        + "If it was not you, ask for another reset straight away and tell your administrator.\n");
    }

    // ------------------------------------------------------------------ internals

    private record Row(UUID id, String hash, boolean disabled) {}
    private record ResetRow(UUID id, String username, String email, String displayName) {}

    private UUID createUser(String username, String email, String displayName, String phone, String jobTitle, String password) {
        Identity.usernameProblem(username).ifPresent(m -> { throw err(HttpStatus.BAD_REQUEST, m); });
        Identity.emailProblem(email).ifPresent(m -> { throw err(HttpStatus.BAD_REQUEST, m); });
        if (displayName == null || displayName.isBlank()) throw err(HttpStatus.BAD_REQUEST, "Please enter your name.");
        PasswordPolicy.problem(password, username, email).ifPresent(m -> { throw err(HttpStatus.BAD_REQUEST, m); });
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user (id, username, email, display_name, phone, job_title, password_hash, password_changed_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())",
                id, username.trim(), Identity.normaliseEmail(email), displayName.trim(),
                phone == null || phone.isBlank() ? null : phone.trim(),
                jobTitle == null || jobTitle.isBlank() ? null : jobTitle.trim(), encoder.encode(password));
        return id;
    }

    private Session openSession(UUID userId, String device) {
        String token = Tokens.create();
        Instant now = Instant.now(), idle = now.plus(SESSION_IDLE);
        jdbc.update("INSERT INTO auth_session (id, user_id, token_hash, device, expires_at, absolute_expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, Tokens.hash(token), device == null ? null : device.substring(0, Math.min(80, device.length())),
                Timestamp.from(idle), Timestamp.from(now.plus(SESSION_ABSOLUTE)));
        return new Session(token, idle, me(userId));
    }

    private void requirePassword(UUID userId, String password) {
        int wait = passwordLimiter.minutesBlocked("p:" + userId);
        if (wait > 0) throw err(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please try again in " + wait + " minute" + (wait == 1 ? "" : "s") + ".");
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, userId);
        if (password == null || !encoder.matches(password, hash)) {
            passwordLimiter.recordFailure("p:" + userId);
            throw err(HttpStatus.FORBIDDEN, "Your current password is not right.");
        }
        passwordLimiter.reset("p:" + userId);
    }

    private boolean emailTaken(String email, UUID except) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE lower(email) = ? AND id <> ?", Integer.class, email, except);
        return n != null && n > 0;
    }

    private Me toMe(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Me(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("display_name"),
                rs.getString("email"), rs.getString("phone"), rs.getString("job_title"));
    }

    private static ResponseStatusException err(HttpStatus s, String m) { return new ResponseStatusException(s, m); }
}
