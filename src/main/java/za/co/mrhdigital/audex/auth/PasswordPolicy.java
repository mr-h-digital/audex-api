package za.co.mrhdigital.audex.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Password rules written for people, not for a checklist: length matters most, so a few random words are welcome.
 * There are no "must contain a symbol" rules. Plain Java (no framework) so it is tested without a server.
 */
public final class PasswordPolicy {
    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 128;

    private static final Set<String> COMMON = Set.of(
            "password", "password1", "password123", "passw0rd", "qwerty123", "qwertyuiop", "1234567890", "12345678910",
            "iloveyou1", "welcome123", "letmein123", "admin12345", "changeme123", "audex12345", "audexaudex",
            "housekeeping", "housekeeping1", "capetown123", "westerncape", "abc1234567", "1q2w3e4r5t", "qazwsxedc1",
            "monkey1234", "football123", "baseball123", "sunshine123", "princess123", "trustno1234", "superman123");

    private PasswordPolicy() {}

    /** The first problem with the password, or empty when it is acceptable. */
    public static Optional<String> problem(String password, String username, String email) {
        if (password == null || password.length() < MIN_LENGTH) {
            return Optional.of("Your password needs at least " + MIN_LENGTH + " characters. A few random words work well, for example: purple kettle river sunrise.");
        }
        if (password.length() > MAX_LENGTH) return Optional.of("That password is too long. Please keep it under " + MAX_LENGTH + " characters.");
        String lower = password.toLowerCase(Locale.ROOT).replace(" ", "");
        if (COMMON.contains(lower)) return Optional.of("That password is too easy to guess. Please choose something less common.");
        if (password.chars().distinct().count() <= 3) return Optional.of("That password repeats the same few characters. Please choose something less predictable.");
        if (username != null && username.length() >= 3 && lower.contains(username.toLowerCase(Locale.ROOT))) {
            return Optional.of("Your password should not contain your username.");
        }
        if (email != null) {
            String local = email.toLowerCase(Locale.ROOT).split("@")[0];
            if (local.length() >= 4 && lower.contains(local)) return Optional.of("Your password should not contain your email address.");
        }
        return Optional.empty();
    }
}
