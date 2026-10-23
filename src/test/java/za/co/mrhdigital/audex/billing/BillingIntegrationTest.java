package za.co.mrhdigital.audex.billing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import za.co.mrhdigital.audex.auth.MailSender;
import za.co.mrhdigital.audex.auth.Tokens;
import za.co.mrhdigital.audex.scoring.TemplateCatalog;
import za.co.mrhdigital.audex.storage.PhotoStorage;

@SpringBootTest
@AutoConfigureMockMvc
@Import(BillingIntegrationTest.Storage.class)
@EnabledIfEnvironmentVariable(named = "AUDEX_TEST_DATABASE_URL", matches = ".+")
class BillingIntegrationTest {
    private static final String SCHEMA = "billing_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String URL = System.getenv("AUDEX_TEST_DATABASE_URL");
    private static final String USER = System.getenv().getOrDefault("AUDEX_TEST_DATABASE_USER", "audex");
    private static final String PASSWORD = System.getenv().getOrDefault("AUDEX_TEST_DATABASE_PASSWORD", "");
    private static final KeyPair KEYS = keys();
    private static KeyPair keys() {
        try { var g = KeyPairGenerator.getInstance("EC"); g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1")); return g.generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        p.add("spring.datasource.username", () -> USER);
        p.add("spring.datasource.password", () -> PASSWORD);
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.flyway.default-schema", () -> SCHEMA);
        p.add("audex.billing.enabled", () -> true);
        p.add("audex.billing.checkout-enabled", () -> true);
        p.add("audex.billing.private-key", () -> Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
        p.add("audex.billing.public-key", () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        p.add("audex.billing.secret-key", () -> "sk_test_fixture_only");
        p.add("audex.billing.reconcile-initial-delay", () -> "3600000");
        p.add("audex.billing.provider-plans.solo-monthly", () -> "PLN_solo");
        p.add("audex.billing.provider-plans.solo-annually", () -> "PLN_solo_year");
        p.add("audex.billing.provider-plans.professional-monthly", () -> "PLN_pro");
        p.add("audex.billing.provider-plans.professional-annually", () -> "PLN_pro_year");
        p.add("audex.auth.registration-enabled", () -> true);
        p.add("audex.storage.bucket", () -> "");
    }
    @AfterAll static void cleanup() throws Exception {
        try (var c = DriverManager.getConnection(URL, USER, PASSWORD); var s = c.createStatement()) {
            s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
        }
    }
    @TestConfiguration static class Storage {
        @Bean PhotoStorage photos() {
            return new PhotoStorage() {
                private final Map<String, StoredPhoto> data = new ConcurrentHashMap<>();
                public void put(String key, byte[] bytes, String type) { data.put(key, new StoredPhoto(bytes, type)); }
                public Optional<StoredPhoto> get(String key) { return Optional.ofNullable(data.get(key)); }
            };
        }
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired TemplateCatalog catalog;
    @Autowired EntitlementService entitlements;
    @Autowired BillingService billing;
    @Autowired BillingEvents events;
    @Autowired PhotoUsageReconciler photoUsage;
    @MockitoBean PaystackClient provider;
    @MockitoBean MailSender mail;
    private UUID user;
    private String token;
    @BeforeEach void account() {
        jdbc.execute("TRUNCATE app_user, billing_event CASCADE");
        user = UUID.randomUUID(); token = Tokens.create();
        jdbc.update("""
                INSERT INTO app_user(id, username, email, display_name, password_hash, email_verified_at, trial_started_at)
                VALUES (?, 'auditor', 'auditor@example.test', 'Auditor', 'unused', now(), now())
                """, user);
        jdbc.update("INSERT INTO auth_session(id, user_id, token_hash, expires_at, absolute_expires_at) VALUES (?, ?, ?, now() + interval '1 day', now() + interval '2 days')",
                UUID.randomUUID(), user, Tokens.hash(token));
        when(provider.checkoutEnabled()).thenReturn(true);
    }
    private JsonNode json(Object body) throws Exception { return mapper.valueToTree(body); }
    private String body(Object body) throws Exception { return mapper.writeValueAsString(body); }
    private Map<String, Object> site(UUID id, long revision, boolean archived) {
        return Map.of("id", id, "name", "Clinic", "revision", revision, "archived", archived);
    }
    private JsonNode audit(UUID site) throws Exception {
        var result = mapper.createObjectNode().put("id", UUID.randomUUID().toString()).put("siteId", site.toString())
                .put("siteName", "Clinic").put("date", "2026-10-23").put("ruleSetCode", "WPC_V1");
        var answers = result.putObject("answers");
        catalog.template().path("sections").forEach(section -> section.path("items")
                .forEach(item -> answers.put(item.path("key").asText(), "COMPLIANT")));
        return result;
    }
    @Test void trialQuotaIncludesRestoreAndAuditCompatibilityPath() throws Exception {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        mvc.perform(put("/api/sites/" + first).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body(site(first, 0, false)))).andExpect(status().isOk());
        mvc.perform(put("/api/sites/" + first).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body(site(first, 0, false)))).andExpect(status().isOk());
        mvc.perform(put("/api/sites/" + second).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body(site(second, 0, false)))).andExpect(status().isConflict());
        JsonNode audit = audit(second);
        mvc.perform(put("/api/audits/" + audit.path("id").asText()).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(audit.toString())).andExpect(status().isConflict());
        mvc.perform(put("/api/sites/" + first).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body(site(first, 1, true)))).andExpect(status().isOk());
        mvc.perform(put("/api/sites/" + second).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body(site(second, 0, false)))).andExpect(status().isOk());
        mvc.perform(put("/api/sites/" + first).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body(site(first, 2, false)))).andExpect(status().isConflict());
    }
    @Test void concurrentSiteCreationCannotOverspendQuota() throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var jobs = java.util.stream.IntStream.range(0, 2).mapToObj(i -> workers.submit(() -> {
                UUID id = UUID.randomUUID(); start.await();
                return mvc.perform(put("/api/sites/" + id).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body(site(id, 0, false)))).andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            var statuses = new java.util.ArrayList<Integer>();
            for (var job : jobs) statuses.add(job.get(20, TimeUnit.SECONDS));
            statuses.sort(Integer::compareTo);
            assertEquals(java.util.List.of(200, 409), statuses);
        }
    }
    @Test void expiryBlocksEveryResourceWriteButKeepsReadsAndEntitlements() throws Exception {
        UUID site = UUID.randomUUID();
        JsonNode audit = audit(site);
        String path = "/api/audits/" + audit.path("id").asText();
        mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(audit.toString())).andExpect(status().isOk());
        UUID photo = UUID.randomUUID();
        mvc.perform(put(path + "/photos/" + photo).header("Authorization", "Bearer " + token).contentType("image/jpeg").content(new byte[]{1})).andExpect(status().isNoContent());
        jdbc.update("UPDATE app_user SET trial_started_at = now() - interval '15 days' WHERE id = ?", user);
        mvc.perform(put("/api/sites/" + site).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body(site(site, 1, true)))).andExpect(status().isPaymentRequired());
        mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(audit.toString())).andExpect(status().isPaymentRequired());
        mvc.perform(put("/api/training-sessions/" + UUID.randomUUID()).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("id", UUID.randomUUID(), "siteId", site, "date", "2026-10-23", "topic", "Safety", "revision", 0)))).andExpect(status().isBadRequest());
        UUID training = UUID.randomUUID();
        mvc.perform(put("/api/training-sessions/" + training).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("id", training, "siteId", site, "date", "2026-10-23", "topic", "Safety", "revision", 0)))).andExpect(status().isPaymentRequired());
        mvc.perform(put(path + "/photos/" + UUID.randomUUID()).header("Authorization", "Bearer " + token).contentType("image/jpeg").content(new byte[]{1})).andExpect(status().isPaymentRequired());
        mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/photos/" + photo).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/sites").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/billing").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andExpect(jsonPath("$.lease.signature").isNotEmpty());
        assertFalse(entitlements.access(user).writable());
        assertFalse(entitlements.access(user).offlineUntil().isAfter(entitlements.access(user).accessUntil()));
    }
    @Test void photoBytesAreCountedExactlyAndRetriesAreFree() throws Exception {
        JsonNode audit = audit(UUID.randomUUID());
        String path = "/api/audits/" + audit.path("id").asText();
        mvc.perform(put(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(audit.toString())).andExpect(status().isOk());
        UUID photo = UUID.randomUUID();
        for (int i = 0; i < 2; i++) mvc.perform(put(path + "/photos/" + photo).header("Authorization", "Bearer " + token).contentType("image/jpeg").content(new byte[]{1, 2, 3})).andExpect(status().isNoContent());
        assertEquals(3, entitlements.access(user).usedBytes());
        jdbc.update("UPDATE audit_photo SET size_bytes = 99999999 WHERE id = ?", photo);
        mvc.perform(put(path + "/photos/" + UUID.randomUUID()).header("Authorization", "Bearer " + token).contentType("image/jpeg").content(new byte[]{1, 2})).andExpect(status().isConflict());
        jdbc.update("UPDATE audit_photo SET size_bytes = NULL WHERE id = ?", photo);
        mvc.perform(put(path + "/photos/" + UUID.randomUUID()).header("Authorization", "Bearer " + token).contentType("image/jpeg").content(new byte[]{1})).andExpect(status().isServiceUnavailable());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> photoUsage.reconcile(user, user));
        jdbc.update("UPDATE app_user SET email = 'info@mrhdigital.co.za' WHERE id = ?", user);
        jdbc.update("INSERT INTO complimentary_candidate(user_id, slot, original_email) VALUES (?, 'administrator', 'info@mrhdigital.co.za')", user);
        entitlements.grant(user, "test", "Verified original administrator fixture");
        assertEquals(new PhotoUsageReconciler.Result(1, 0), photoUsage.reconcile(user, user));
        assertEquals(3, entitlements.access(user).usedBytes());
    }
    private JsonNode payment(String reference, String plan, long amount) throws Exception {
        return json(Map.of("reference", reference, "status", "success", "currency", "ZAR", "domain", "test",
                "amount", amount, "plan", plan, "customer", Map.of("customer_code", "CUS_fixture"),
                "paid_at", Instant.now().minusSeconds(2).toString()));
    }
    private void checkout(String reference, String plan, String cadence, long amount, String providerPlan) {
        jdbc.update("INSERT INTO billing_checkout(reference, user_id, plan, cadence, amount, provider_plan) VALUES (?, ?, ?, ?, ?, ?)", reference, user, plan, cadence, amount, providerPlan);
    }
    @Test void verifiedPaymentsAreIdempotentAndWrongAmountsCannotActivateAccess() throws Exception {
        checkout("audex-fixture", "SOLO", "monthly", 19900, "PLN_solo");
        when(provider.get("/transaction/verify/audex-fixture")).thenReturn(payment("audex-fixture", "PLN_solo", 199));
        assertThrows(IllegalStateException.class, () -> billing.refresh(user));
        assertEquals("TRIAL", entitlements.access(user).plan());
        when(provider.get("/transaction/verify/audex-fixture")).thenReturn(payment("audex-fixture", "PLN_solo", 19900));
        billing.refresh(user); billing.process("charge.success", "audex-fixture", null);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM billing_payment", Integer.class));
        assertEquals("SOLO", entitlements.access(user).plan());
        assertEquals(3, entitlements.access(user).siteLimit());
        assertEquals(1_000_000_000L, entitlements.access(user).storageBytes());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> billing.cancel(UUID.randomUUID(), "SUB_fixture"));
    }
    @Test void checkoutSurvivesProviderTimeoutAndDoesNotCreateADuplicateChargeableIntent() throws Exception {
        when(provider.get("/plan/PLN_solo")).thenReturn(json(Map.of("amount", 19900, "currency", "ZAR", "interval", "monthly")));
        when(provider.post(eq("/transaction/initialize"), anyMap())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "Fixture timeout"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> billing.checkout(user, "SOLO", "monthly"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM billing_checkout WHERE user_id = ?", Integer.class, user));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> billing.checkout(user, "SOLO", "monthly"));
        verify(provider, times(1)).post(eq("/transaction/initialize"), anyMap());
        String ref = jdbc.queryForObject("SELECT reference FROM billing_checkout WHERE user_id = ?", String.class, user);
        when(provider.get("/transaction/verify/" + ref)).thenReturn(payment(ref, "PLN_solo", 19900));
        billing.refresh(user);
        assertEquals("SOLO", entitlements.access(user).plan());
    }
    @Test void checkoutUsesServerAmountAndResumesTheSameHostedUrl() throws Exception {
        when(provider.get("/plan/PLN_solo")).thenReturn(json(Map.of("amount", 19900, "currency", "ZAR", "interval", "monthly")));
        when(provider.post(eq("/transaction/initialize"), anyMap())).thenReturn(json(Map.of("authorization_url", "https://checkout.paystack.com/fixture")));
        var first = billing.checkout(user, "SOLO", "monthly");
        var second = billing.checkout(user, "SOLO", "monthly");
        assertEquals(first, second);
        verify(provider, times(1)).post(eq("/transaction/initialize"), argThat(request ->
                Long.valueOf(19900).equals(request.get("amount")) && "ZAR".equals(request.get("currency"))
                        && "PLN_solo".equals(request.get("plan"))));
        assertTrue(first.testMode());
        assertEquals("TRIAL", entitlements.access(user).plan());
    }
    @Test void durableOutOfOrderEventsRetryAndRenewalsRequireTheVerifiedSubscriptionCustomer() throws Exception {
        checkout("audex-initial", "SOLO", "monthly", 19900, "PLN_solo");
        when(provider.get("/subscription/SUB_fixture")).thenReturn(json(Map.of("subscription_code", "SUB_fixture", "status", "active",
                "customer", Map.of("customer_code", "CUS_fixture"), "plan", Map.of("plan_code", "PLN_solo"))));
        byte[] event = body(Map.of("event", "subscription.create", "data", Map.of("subscription_code", "SUB_fixture"))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var mac = javax.crypto.Mac.getInstance("HmacSHA512");
        mac.init(new javax.crypto.spec.SecretKeySpec("sk_test_fixture_only".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA512"));
        String signature = java.util.HexFormat.of().formatHex(mac.doFinal(event));
        mvc.perform(post("/api/billing/webhook").header("x-paystack-signature", "invalid").contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isUnauthorized());
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/billing/webhook").header("x-paystack-signature", signature).contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isOk());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM billing_event", Integer.class));
        events.reconcile();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM billing_event WHERE processed_at IS NULL AND attempts = 1", Integer.class));
        when(provider.get("/transaction/verify/audex-initial")).thenReturn(payment("audex-initial", "PLN_solo", 19900));
        billing.refresh(user);
        events.reconcile();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM billing_event WHERE processed_at IS NULL", Integer.class));
        var wrongCustomer = payment("renewal-fixture", "PLN_solo", 19900).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) wrongCustomer.path("customer")).put("customer_code", "CUS_different");
        when(provider.get("/transaction/verify/renewal-fixture")).thenReturn(wrongCustomer);
        assertThrows(IllegalStateException.class, () -> billing.process("invoice.update", "renewal-fixture", "SUB_fixture"));
        when(provider.get("/transaction/verify/renewal-fixture")).thenReturn(payment("renewal-fixture", "PLN_solo", 19900));
        billing.process("invoice.update", "renewal-fixture", "SUB_fixture");
        billing.process("invoice.update", "renewal-fixture", "SUB_fixture");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM billing_payment", Integer.class));
        billing.process("charge.success", "audex-initial", null);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM billing_payment", Integer.class));
    }
    @Test void annualRenewalCancellationAndRefundAreAccountBound() throws Exception {
        checkout("audex-initial", "PROFESSIONAL", "annually", 399000, "PLN_pro_year");
        when(provider.get("/transaction/verify/audex-initial")).thenReturn(payment("audex-initial", "PLN_pro_year", 399000));
        billing.refresh(user);
        assertEquals("PROFESSIONAL", entitlements.access(user).plan());
        Instant until = entitlements.access(user).accessUntil();
        when(provider.get("/subscription/SUB_fixture")).thenReturn(json(Map.of("subscription_code", "SUB_fixture", "status", "active", "email_token", "fixture",
                "customer", Map.of("customer_code", "CUS_fixture"), "plan", Map.of("plan_code", "PLN_pro_year"))));
        billing.process("subscription.create", null, "SUB_fixture");
        when(provider.post(eq("/subscription/disable"), anyMap())).thenReturn(json(Map.of()));
        billing.cancel(user, "SUB_fixture");
        assertEquals(until, entitlements.access(user).accessUntil());
        assertTrue((Boolean) billing.subscriptions(user).getFirst().get("cancel_requested"));
        billing.process("refund.processed", "audex-initial", null);
        assertEquals("TRIAL", entitlements.access(user).plan());
    }
    @Test void onlyVerifiedFrozenCandidatesCanReceiveComplimentaryAccess() throws Exception {
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> entitlements.grant(user, "test", "Ownership checked"));
        jdbc.update("UPDATE app_user SET email = 'info@mrhdigital.co.za' WHERE id = ?", user);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> entitlements.grant(user, "test", "Email changes cannot grant access"));
        jdbc.update("INSERT INTO complimentary_candidate(user_id, slot, original_email) VALUES (?, 'administrator', 'info@mrhdigital.co.za')", user);
        jdbc.update("UPDATE app_user SET email_verified_at = NULL WHERE id = ?", user);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> entitlements.grant(user, "test", "Not verified"));
        jdbc.update("UPDATE app_user SET email_verified_at = now() WHERE id = ?", user);
        entitlements.grant(user, "test", "Verified ownership of the frozen candidate account");
        assertEquals("COMPLIMENTARY", entitlements.access(user).plan());
        assertTrue(entitlements.access(user).administrator());
        assertNull(entitlements.access(user).accessUntil());
    }
    @Test void trainerGrantNeverProvidesAdministratorAccess() {
        jdbc.update("UPDATE app_user SET email = 'hktrainer@wpc.co.za' WHERE id = ?", user);
        jdbc.update("INSERT INTO complimentary_candidate(user_id, slot, original_email) VALUES (?, 'trainer', 'hktrainer@wpc.co.za')", user);
        entitlements.grant(user, "test", "Verified original trainer fixture");
        assertEquals("COMPLIMENTARY", entitlements.access(user).plan());
        assertTrue(entitlements.access(user).writable());
        assertFalse(entitlements.access(user).administrator());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> entitlements.requireAdmin(user));
    }
    @Test void signupRequiresEmailVerificationAndNeverInfersAComplimentaryRole() throws Exception {
        var signup = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body(Map.of(
                "username", "newowner", "email", "hktrainer@wpc.co.za", "displayName", "New user", "password", "seven green clouds float"))))
                .andExpect(status().isOk()).andReturn();
        UUID registered = UUID.fromString(mapper.readTree(signup.getResponse().getContentAsByteArray()).path("user").path("id").asText());
        assertFalse(entitlements.access(registered).writable());
        assertFalse(entitlements.access(registered).administrator());
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mail).send(eq("hktrainer@wpc.co.za"), anyString(), captor.capture());
        String content = captor.getValue();
        String verificationToken = content.substring(content.indexOf("#token=") + 7).split("\\s")[0];
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("token", verificationToken)))).andExpect(status().isOk());
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content(body(Map.of("token", verificationToken)))).andExpect(status().isBadRequest());
        assertTrue(entitlements.access(registered).writable());
        assertEquals("TRIAL", entitlements.access(registered).plan());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> entitlements.grant(registered, "test", "Cannot grant to a public signup"));
    }
}
