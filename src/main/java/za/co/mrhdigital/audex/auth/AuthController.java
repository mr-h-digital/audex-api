package za.co.mrhdigital.audex.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public record SetupBody(String code, String username, String email, String displayName, String phone, String jobTitle, String password) {}
    public record LoginBody(String username, String password) {}
    public record EmailBody(String email) {}
    public record IdentifierBody(String identifier) {}
    public record ResetBody(String token, String password) {}
    public record RegisterBody(String username, String email, String displayName, String password) {}
    public record VerifyBody(String token) {}

    private static final Map<String, String> SAME_ANSWER = Map.of("message",
            "If that matches an account, an email is on its way. Please check your inbox (and junk folder).");

    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    /** Lets the app know whether to show first-time setup or sign-in. */
    @GetMapping("/status")
    public Map<String, Object> status() { return Map.of("setupRequired", auth.setupRequired(), "registrationEnabled", auth.registrationEnabled()); }

    @PostMapping("/register")
    public AuthService.Session register(@RequestBody RegisterBody b, HttpServletRequest req) {
        return auth.register(b.username(), b.email(), b.displayName(), b.password(), req.getHeader("User-Agent"), clientIp(req));
    }

    @PostMapping("/verify-email")
    public Map<String, String> verify(@RequestBody VerifyBody b) {
        auth.verifyEmail(b.token());
        return Map.of("message", "Email verified. You can sign in and continue.");
    }

    @PostMapping("/setup")
    public AuthService.Session setup(@RequestBody SetupBody b, HttpServletRequest req) {
        return auth.setup(b.code(), b.username(), b.email(), b.displayName(), b.phone(), b.jobTitle(), b.password(), req.getHeader("User-Agent"));
    }

    @PostMapping("/login")
    public AuthService.Session login(@RequestBody LoginBody b, HttpServletRequest req) {
        return auth.login(b.username(), b.password(), req.getHeader("User-Agent"), clientIp(req));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestAttribute(value = AuthInterceptor.ATTRIBUTE, required = false) AuthUser user, HttpServletRequest req) {
        // Authenticate here ourselves, because /api/auth/** is open to the interceptor.
        AuthUser u = user != null ? user : fromHeader(req);
        if (u != null) auth.logout(u.sessionId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(HttpServletRequest req) {
        AuthUser u = fromHeader(req);
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in.");
        auth.logoutEverywhere(u.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/forgot-username")
    public Map<String, String> forgotUsername(@RequestBody EmailBody b) { auth.forgotUsername(b.email()); return SAME_ANSWER; }

    @PostMapping("/forgot-password")
    public Map<String, String> forgotPassword(@RequestBody IdentifierBody b) { auth.forgotPassword(b.identifier()); return SAME_ANSWER; }

    @PostMapping("/reset-password")
    public Map<String, String> reset(@RequestBody ResetBody b) {
        auth.resetPassword(b.token(), b.password());
        return Map.of("message", "Your password has been changed. Please sign in.");
    }

    private AuthUser fromHeader(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        String token = h != null && h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7).trim() : null;
        return auth.authenticate(token).orElse(null);
    }

    /** Railway puts the real address in X-Forwarded-For; the last entry is the one its proxy added. */
    static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        return req.getRemoteAddr();
    }
}
