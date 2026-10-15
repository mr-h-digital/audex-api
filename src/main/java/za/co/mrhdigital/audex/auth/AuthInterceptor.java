package za.co.mrhdigital.audex.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.web.servlet.HandlerInterceptor;

/** Requires a valid sign-in on every /api call except the sign-in and recovery calls themselves. */
public class AuthInterceptor implements HandlerInterceptor {
    public static final String ATTRIBUTE = "audex.user";
    private final AuthService auth;

    public AuthInterceptor(AuthService auth) { this.auth = auth; }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;
        String header = req.getHeader("Authorization");
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : null;
        Optional<AuthUser> user = auth.authenticate(token);
        if (user.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            res.setContentType("application/json");
            res.getWriter().write("{\"message\":\"Please sign in.\"}");
            return false;
        }
        req.setAttribute(ATTRIBUTE, user.get());
        return true;
    }
}
