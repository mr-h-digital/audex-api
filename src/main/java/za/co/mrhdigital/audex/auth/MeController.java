package za.co.mrhdigital.audex.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in person's own account. */
@RestController
@RequestMapping("/api/me")
public class MeController {
    public record ProfileBody(String displayName, String email, String phone, String currentPassword) {}
    public record PasswordBody(String currentPassword, String newPassword) {}

    private final AuthService auth;

    public MeController(AuthService auth) { this.auth = auth; }

    @GetMapping
    public AuthService.Me get(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user) { return auth.me(user.id()); }

    @PatchMapping
    public AuthService.Me update(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user, @RequestBody ProfileBody b) {
        return auth.updateMe(user.id(), b.displayName(), b.email(), b.phone(), b.currentPassword());
    }

    @PostMapping("/password")
    public ResponseEntity<Void> password(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user, @RequestBody PasswordBody b) {
        auth.changePassword(user.id(), user.sessionId(), b.currentPassword(), b.newPassword());
        return ResponseEntity.noContent().build();
    }
}
