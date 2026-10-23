package za.co.mrhdigital.audex.billing;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public enum Plan {
    TRIAL(1, 100_000_000L, 0),
    SOLO(3, 1_000_000_000L, 19_900),
    PROFESSIONAL(15, 5_000_000_000L, 39_900),
    COMPLIMENTARY(Integer.MAX_VALUE, Long.MAX_VALUE, 0);

    public final int siteLimit;
    public final long storageBytes;
    public final long monthlyCents;
    Plan(int sites, long storage, long cents) { siteLimit = sites; storageBytes = storage; monthlyCents = cents; }

    public long amount(String cadence) {
        if (this != SOLO && this != PROFESSIONAL) throw invalid();
        return switch (cadence == null ? "" : cadence) {
            case "monthly" -> monthlyCents;
            case "annually" -> monthlyCents * 10;
            default -> throw invalid();
        };
    }
    public static Plan paid(String code) {
        try {
            Plan plan = valueOf(code == null ? "" : code);
            if (plan == SOLO || plan == PROFESSIONAL) return plan;
        } catch (IllegalArgumentException ignored) { /* Invalid values receive a client error below. */ }
        throw invalid();
    }
    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose Solo or Professional and monthly or annual billing.");
    }
}
