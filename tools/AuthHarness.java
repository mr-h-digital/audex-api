import java.util.concurrent.atomic.AtomicLong;
import za.co.mrhdigital.audex.auth.*;

/** Runs the plain-Java auth rules without Maven. Mirrors AuthCoreTest. */
public class AuthHarness {
    static int pass = 0, fail = 0;
    static void check(String name, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL " + name); } }
    public static void main(String[] a) {
        check("short", PasswordPolicy.problem("short1", "chernay", "c@x.co").isPresent());
        check("words", PasswordPolicy.problem("purple kettle river sunrise", "chernay", "c@x.co").isEmpty());
        check("common", PasswordPolicy.problem("Password123", "chernay", null).isPresent());
        check("repeat", PasswordPolicy.problem("aaaaaaaaaaaa", "chernay", null).isPresent());
        check("username in pw", PasswordPolicy.problem("my-chernay-secret", "chernay", null).isPresent());
        check("email in pw", PasswordPolicy.problem("hildebrandt-keys-2026", "x1", "hildebrandt@x.co").isPresent());
        check("too long", PasswordPolicy.problem("x".repeat(129), "a", null).isPresent());
        String t1 = Tokens.create(), t2 = Tokens.create();
        check("tokens differ", !t1.equals(t2)); check("token length", t1.length() >= 43);
        check("hash stable", Tokens.hash(t1).equals(Tokens.hash(t1))); check("hash length", Tokens.hash(t1).length() == 64);
        check("hash differs", !Tokens.hash(t1).equals(t1));
        check("ct eq", Tokens.constantTimeEquals("abc", "abc")); check("ct ne", !Tokens.constantTimeEquals("abc", "abd")); check("ct null", !Tokens.constantTimeEquals(null, "abc"));
        AtomicLong now = new AtomicLong(1_000_000);
        AttemptLimiter l = new AttemptLimiter(3, 15 * 60_000, now::get);
        l.recordFailure("u"); check("1 fail ok", l.minutesBlocked("u") == 0); l.recordFailure("u"); check("2 fail ok", l.minutesBlocked("u") == 0);
        l.recordFailure("u"); check("blocked 15", l.minutesBlocked("u") == 15); check("others free", l.minutesBlocked("x") == 0);
        now.addAndGet(10 * 60_000); check("5 left", l.minutesBlocked("u") == 5);
        now.addAndGet(5 * 60_000 + 1); check("released", l.minutesBlocked("u") == 0);
        AttemptLimiter l2 = new AttemptLimiter(2, 60_000, () -> 5L); l2.recordFailure("u"); l2.recordFailure("u");
        check("blocked", l2.minutesBlocked("u") > 0); l2.reset("u"); check("reset", l2.minutesBlocked("u") == 0);
        check("user ok", Identity.usernameProblem("chernay.h").isEmpty()); check("user short", Identity.usernameProblem("ab").isPresent());
        check("user space", Identity.usernameProblem("has space").isPresent());
        check("email ok", Identity.emailProblem("chernay@example.com").isEmpty()); check("email bad", Identity.emailProblem("nope").isPresent());
        check("norm user", Identity.normaliseUsername("  Chernay ").equals("chernay")); check("norm email", Identity.normaliseEmail(" A@B.co ").equals("a@b.co"));
        System.out.println("Auth core: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
