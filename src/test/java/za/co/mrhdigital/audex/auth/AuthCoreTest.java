package za.co.mrhdigital.audex.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AuthCoreTest {

    @Test void passwordNeedsLengthNotSymbols() {
        assertTrue(PasswordPolicy.problem("short1", "chernay", "c@x.co").isPresent());
        assertTrue(PasswordPolicy.problem("purple kettle river sunrise", "chernay", "c@x.co").isEmpty());
    }
    @Test void rejectsCommonRepeatedAndPersonalPasswords() {
        assertTrue(PasswordPolicy.problem("Password123", "chernay", null).isPresent());
        assertTrue(PasswordPolicy.problem("aaaaaaaaaaaa", "chernay", null).isPresent());
        assertTrue(PasswordPolicy.problem("my-chernay-secret", "chernay", null).isPresent());
        assertTrue(PasswordPolicy.problem("hildebrandt-keys-2026", "x1", "hildebrandt@x.co").isPresent());
        assertTrue(PasswordPolicy.problem("x".repeat(129), "a", null).isPresent());
    }
    @Test void tokensAreRandomAndHashedStably() {
        String a = Tokens.create(), b = Tokens.create();
        assertNotEquals(a, b);
        assertTrue(a.length() >= 43);
        assertEquals(Tokens.hash(a), Tokens.hash(a));
        assertNotEquals(Tokens.hash(a), a);
        assertEquals(64, Tokens.hash(a).length());
        assertTrue(Tokens.constantTimeEquals("abc", "abc"));
        assertFalse(Tokens.constantTimeEquals("abc", "abd"));
        assertFalse(Tokens.constantTimeEquals(null, "abc"));
    }
    @Test void limiterBlocksAfterMaxFailuresAndReleasesLater() {
        AtomicLong now = new AtomicLong(1_000_000);
        AttemptLimiter l = new AttemptLimiter(3, 15 * 60_000, now::get);
        for (int i = 0; i < 2; i++) { l.recordFailure("u"); assertEquals(0, l.minutesBlocked("u")); }
        l.recordFailure("u");
        assertEquals(15, l.minutesBlocked("u"));
        assertEquals(0, l.minutesBlocked("someone-else"));
        now.addAndGet(10 * 60_000);
        assertEquals(5, l.minutesBlocked("u"));
        now.addAndGet(5 * 60_000 + 1);
        assertEquals(0, l.minutesBlocked("u"));
    }
    @Test void successClearsTheCount() {
        AttemptLimiter l = new AttemptLimiter(2, 60_000, () -> 5L);
        l.recordFailure("u"); l.recordFailure("u");
        assertTrue(l.minutesBlocked("u") > 0);
        l.reset("u");
        assertEquals(0, l.minutesBlocked("u"));
    }
    @Test void usernameAndEmailRules() {
        assertTrue(Identity.usernameProblem("chernay.h").isEmpty());
        assertTrue(Identity.usernameProblem("ab").isPresent());
        assertTrue(Identity.usernameProblem("has space").isPresent());
        assertTrue(Identity.emailProblem("chernay@example.com").isEmpty());
        assertTrue(Identity.emailProblem("nope").isPresent());
        assertEquals("chernay", Identity.normaliseUsername("  Chernay "));
        assertEquals("a@b.co", Identity.normaliseEmail(" A@B.co "));
    }
}
