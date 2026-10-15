package za.co.mrhdigital.audex.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Rules for usernames and email addresses. Plain Java so they are tested without a server. */
public final class Identity {
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{2,31}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    private Identity() {}

    public static Optional<String> usernameProblem(String u) {
        if (u == null || !USERNAME.matcher(u.trim()).matches()) {
            return Optional.of("Your username must be 3 to 32 characters: letters, numbers, dots, dashes or underscores.");
        }
        return Optional.empty();
    }

    public static Optional<String> emailProblem(String e) {
        if (e == null || e.length() > 254 || !EMAIL.matcher(e.trim()).matches()) return Optional.of("That email address does not look right.");
        return Optional.empty();
    }

    public static String normaliseUsername(String u) { return u == null ? "" : u.trim().toLowerCase(Locale.ROOT); }

    public static String normaliseEmail(String e) { return e == null ? "" : e.trim().toLowerCase(Locale.ROOT); }
}
