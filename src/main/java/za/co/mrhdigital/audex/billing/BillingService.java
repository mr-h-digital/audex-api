package za.co.mrhdigital.audex.billing;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BillingService {
    public record Checkout(String url, String reference, boolean testMode) {}
    private final JdbcTemplate jdbc;
    private final BillingConfig.Properties config;
    private final PaystackClient provider;
    private final EntitlementService entitlements;
    private final Clock clock;
    private final String appUrl;
    private final TransactionTemplate transaction;
    private record Intent(String reference, String email, String url) {}

    public BillingService(JdbcTemplate jdbc, BillingConfig.Properties config, PaystackClient provider,
                          EntitlementService entitlements, Clock clock, @Value("${audex.app-url}") String appUrl,
                          PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.config = config; this.provider = provider; this.entitlements = entitlements;
        this.clock = clock; this.appUrl = appUrl.replaceAll("/+$", "");
        transaction = new TransactionTemplate(manager);
        if (config.checkoutEnabled() && config.livePayments() && !this.appUrl.startsWith("https://")) throw new IllegalStateException("Live checkout requires an HTTPS app URL.");
    }

    public Checkout checkout(UUID user, String planCode, String cadence) {
        Plan plan = Plan.paid(planCode);
        long amount = plan.amount(cadence);
        if (!provider.checkoutEnabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Checkout has not been enabled yet.");
        String providerPlan = config.providerPlans().get(plan.name().toLowerCase(Locale.ROOT) + "-" + cadence);
        JsonNode actual = provider.get("/plan/" + providerPlan);
        validatePlan(actual, plan, cadence);
        Intent intent = transaction.execute(status -> {
            entitlements.lock(user);
            var a = entitlements.access(user);
            if (!a.verified()) throw error("Verify your email before choosing a subscription.");
            if (a.plan().equals("COMPLIMENTARY")) throw error("This account already has complimentary full access.");
            if (jdbc.queryForObject("SELECT count(*) FROM billing_payment WHERE user_id = ? AND NOT reversed AND paid_until > now()", Integer.class, user) > 0) {
                throw error("You already have paid access. Cancel renewal first; choose a different plan after your current paid period ends.");
            }
            for (String code : jdbc.queryForList("SELECT code FROM billing_subscription WHERE user_id = ?", String.class, user)) {
                linkSubscription(code);
            }
            if (jdbc.queryForObject("SELECT count(*) FROM billing_subscription WHERE user_id = ? AND NOT cancel_requested", Integer.class, user) > 0) {
                throw error("Cancel the existing renewal before starting another subscription.");
            }
            var pending = jdbc.queryForList("SELECT * FROM billing_checkout WHERE user_id = ? AND paid_at IS NULL ORDER BY created_at DESC LIMIT 1", user);
            if (!pending.isEmpty()) {
                var old = pending.getFirst();
                if (!plan.name().equals(old.get("plan")) || !cadence.equals(old.get("cadence"))) {
                    throw error("A checkout for " + old.get("plan") + " (" + old.get("cadence") + ") is already pending. Resume that choice or contact support.");
                }
                if (old.get("checkout_url") != null) return new Intent((String) old.get("reference"), null, (String) old.get("checkout_url"));
                throw error("A checkout is pending confirmation. Refresh billing or contact support before trying again; no second subscription will be created.");
            }
            String reference = "audex-" + UUID.randomUUID();
            String email = jdbc.queryForObject("SELECT email FROM app_user WHERE id = ?", String.class, user);
            jdbc.update("INSERT INTO billing_checkout(reference, user_id, plan, cadence, amount, provider_plan) VALUES (?, ?, ?, ?, ?, ?)",
                    reference, user, plan.name(), cadence, amount, providerPlan);
            return new Intent(reference, email, null);
        });
        if (intent == null) throw new IllegalStateException("Checkout intent was not persisted.");
        if (intent.url() != null) return new Checkout(intent.url(), intent.reference(), !config.livePayments());
        // Persist intent BEFORE contacting Paystack: a timeout must not orphan a chargeable subscription.
        JsonNode result = provider.post("/transaction/initialize", Map.of("email", intent.email(), "amount", amount, "currency", "ZAR",
                "reference", intent.reference(), "plan", providerPlan, "channels", List.of("card"),
                "callback_url", appUrl + "/?billing=return", "metadata", Map.of("audex_account_id", user.toString())));
        String url = safeUrl(result.path("authorization_url").asText(), "checkout.paystack.com");
        jdbc.update("UPDATE billing_checkout SET checkout_url = ? WHERE reference = ?", url, intent.reference());
        return new Checkout(url, intent.reference(), !config.livePayments());
    }

    @Transactional
    public void refresh(UUID user) {
        entitlements.lock(user);
        for (String ref : jdbc.queryForList("SELECT reference FROM billing_checkout WHERE user_id = ? AND paid_at IS NULL ORDER BY created_at DESC LIMIT 5", String.class, user)) {
            JsonNode payment = provider.get("/transaction/verify/" + ref);
            if ("success".equals(payment.path("status").asText())) acceptInitial(ref, payment);
        }
    }

    public List<Map<String, Object>> subscriptions(UUID user) {
        return jdbc.queryForList("SELECT code, cancel_requested FROM billing_subscription WHERE user_id = ? ORDER BY code", user);
    }

    @Transactional
    public void cancel(UUID user, String code) {
        entitlements.lock(user);
        var own = jdbc.queryForList("SELECT code FROM billing_subscription WHERE code = ? AND user_id = ?", String.class, code, user);
        if (own.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found.");
        JsonNode sub = provider.get("/subscription/" + segment(code));
        provider.post("/subscription/disable", Map.of("code", code, "token", required(sub, "email_token")));
        jdbc.update("UPDATE billing_subscription SET cancel_requested = true, updated_at = now() WHERE code = ?", code);
        audit(user, "renewal-cancelled", "user:" + user, code);
    }

    public String manage(UUID user, String code) {
        if (jdbc.queryForObject("SELECT count(*) FROM billing_subscription WHERE code = ? AND user_id = ?", Integer.class, code, user) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Subscription not found.");
        }
        return safeUrl(provider.get("/subscription/" + segment(code) + "/manage/link").path("link").asText(), "paystack.com");
    }

    @Transactional
    public void process(String event, String reference, String code) {
        if (event.equals("charge.success")) {
            JsonNode payment = provider.get("/transaction/verify/" + segment(reference));
            if (jdbc.queryForObject("SELECT count(*) FROM billing_checkout WHERE reference = ?", Integer.class, reference) == 1) acceptInitial(reference, payment);
            // Recurring charges are bound through the verified invoice's subscription, never just an email.
        } else if (event.startsWith("subscription.")) {
            linkSubscription(code);
        } else if (event.equals("invoice.update")) {
            // The signed invoice binds the reference to a subscription; verify both independently with Paystack.
            linkSubscription(code);
            acceptRenewal(code, reference, provider.get("/transaction/verify/" + segment(reference)));
        } else if (event.equals("refund.processed") || event.equals("charge.dispute.create")) {
            // Conservatively suspend only the affected verified payment, retaining all records and exports.
            jdbc.update("UPDATE billing_payment SET reversed = true WHERE reference = ?", reference);
        }
    }

    private void acceptInitial(String reference, JsonNode payment) {
        var checkout = jdbc.queryForMap("SELECT * FROM billing_checkout WHERE reference = ?", reference);
        UUID user = (UUID) checkout.get("user_id");
        entitlements.lock(user);
        validatePayment(payment, reference, ((Number) checkout.get("amount")).longValue(), (String) checkout.get("provider_plan"));
        String customer = required(payment.path("customer"), "customer_code");
        Instant paidAt = paidAt(payment);
        writePayment(reference, user, null, (String) checkout.get("plan"), (String) checkout.get("cadence"),
                ((Number) checkout.get("amount")).longValue(), paidAt);
        jdbc.update("UPDATE billing_checkout SET customer_code = ?, paid_at = ? WHERE reference = ?",
                customer, Timestamp.from(paidAt), reference);
        jdbc.update("""
                UPDATE billing_subscription SET user_id = ? WHERE customer_code = ? AND provider_plan = ?
                AND (user_id IS NULL OR user_id = ?)
                """, user, customer, checkout.get("provider_plan"), user);
    }

    private void linkSubscription(String code) {
        JsonNode sub = provider.get("/subscription/" + segment(code));
        String customer = required(sub.path("customer"), "customer_code");
        String plan = required(sub.path("plan"), "plan_code");
        List<UUID> owners = jdbc.queryForList("""
                SELECT DISTINCT user_id FROM billing_checkout WHERE customer_code = ? AND provider_plan = ? AND paid_at IS NOT NULL
                """, UUID.class, customer, plan);
        if (owners.size() != 1) throw new IllegalStateException("Subscription cannot yet be linked to one verified AudEx checkout.");
        UUID user = owners.getFirst();
        entitlements.lock(user);
        boolean cancelled = List.of("non-renewing", "cancelled", "completed", "complete").contains(sub.path("status").asText());
        jdbc.update("""
                INSERT INTO billing_subscription(code, customer_code, provider_plan, user_id, cancel_requested) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (code) DO UPDATE SET cancel_requested = EXCLUDED.cancel_requested, updated_at = now()
                """, code, customer, plan, user, cancelled);
        var saved = jdbc.queryForMap("SELECT * FROM billing_subscription WHERE code = ?", code);
        if (!user.equals(saved.get("user_id")) || !customer.equals(saved.get("customer_code")) || !plan.equals(saved.get("provider_plan"))) {
            throw new IllegalStateException("Subscription ownership does not match.");
        }
    }

    private void acceptRenewal(String code, String reference, JsonNode payment) {
        var subscription = jdbc.queryForMap("SELECT * FROM billing_subscription WHERE code = ?", code);
        UUID user = (UUID) subscription.get("user_id");
        entitlements.lock(user);
        var checkouts = jdbc.queryForList("""
                SELECT * FROM billing_checkout WHERE user_id = ? AND provider_plan = ? AND paid_at IS NOT NULL
                ORDER BY paid_at DESC LIMIT 1
                """, user, subscription.get("provider_plan"));
        if (checkouts.isEmpty()) throw new IllegalStateException("No verified initial payment for subscription.");
        var initial = checkouts.getFirst();
        validatePayment(payment, reference, ((Number) initial.get("amount")).longValue(), (String) subscription.get("provider_plan"));
        if (!subscription.get("customer_code").equals(required(payment.path("customer"), "customer_code"))) throw new IllegalStateException("Renewal customer mismatch.");
        writePayment(reference, user, code, (String) initial.get("plan"), (String) initial.get("cadence"),
                ((Number) initial.get("amount")).longValue(), paidAt(payment));
    }

    private void writePayment(String ref, UUID user, String code, String plan, String cadence, long amount, Instant paidAt) {
        // Provider timestamps, not receipt time, make duplicate and delayed events harmless.
        Instant until = periodEnd(paidAt, cadence);
        int changed = jdbc.update("""
                INSERT INTO billing_payment(reference, user_id, subscription_code, plan, cadence, amount, paid_at, paid_until)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, ref, user, code, plan, cadence, amount, Timestamp.from(paidAt), Timestamp.from(until));
        if (changed == 1) audit(user, "payment-verified", "paystack", ref);
        jdbc.update("""
                UPDATE billing_payment SET reversed = true WHERE reference = ? AND EXISTS
                (SELECT 1 FROM billing_event WHERE reference = ? AND event_type IN ('refund.processed', 'charge.dispute.create'))
                """, ref, ref);
        var saved = jdbc.queryForMap("SELECT user_id, amount FROM billing_payment WHERE reference = ?", ref);
        if (!user.equals(saved.get("user_id")) || amount != ((Number) saved.get("amount")).longValue()) throw new IllegalStateException("Payment reference is already allocated to a different account or amount.");
    }
    static Instant periodEnd(Instant paidAt, String cadence) {
        var date = paidAt.atZone(ZoneOffset.UTC);
        if (cadence.equals("annually")) return date.plusYears(1).toInstant();
        var next = date.plusMonths(1);
        // Paystack moves subscriptions started on the 29th-31st to the 28th for future monthly charges.
        if (date.getDayOfMonth() > 28) next = next.withDayOfMonth(28);
        return next.toInstant();
    }

    private void validatePayment(JsonNode p, String ref, long amount, String plan) {
        if (!"success".equals(p.path("status").asText()) || !ref.equals(p.path("reference").asText())
                || amount != p.path("amount").asLong(-1) || !"ZAR".equals(p.path("currency").asText())
                || !(config.livePayments() ? "live" : "test").equals(p.path("domain").asText())) {
            throw new IllegalStateException("Payment status, reference, amount, currency or environment does not match.");
        }
        String actual = p.path("plan").isTextual() ? p.path("plan").asText() : p.path("plan").path("plan_code").asText();
        if (!plan.equals(actual)) throw new IllegalStateException("Payment plan does not match the server checkout.");
        paidAt(p);
    }
    private static void validatePlan(JsonNode p, Plan plan, String cadence) {
        if (p.path("amount").asLong(-1) != plan.amount(cadence) || !"ZAR".equals(p.path("currency").asText())
                || !cadence.equals(p.path("interval").asText()) || p.path("invoice_limit").asInt(0) != 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The payment plan configuration does not match AudEx pricing. Please contact support.");
        }
    }
    private Instant paidAt(JsonNode p) {
        Instant paid = Instant.parse(required(p, "paid_at"));
        if (paid.isAfter(clock.instant().plusSeconds(300))) throw new IllegalStateException("Payment timestamp is in the future.");
        return paid;
    }
    private void audit(UUID user, String action, String actor, String reason) {
        jdbc.update("INSERT INTO billing_audit(user_id, action, actor, reason) VALUES (?, ?, ?, ?)", user, action, actor, reason);
    }
    static String required(JsonNode node, String name) {
        String value = node.path(name).asText("");
        if (value.isBlank()) throw new IllegalStateException("Payment provider omitted " + name + ".");
        return value;
    }
    static String segment(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,150}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment reference.");
        return value;
    }
    private static String safeUrl(String value, String host) {
        URI uri = URI.create(value);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !(uri.getHost().equals(host) || uri.getHost().endsWith("." + host))
                || uri.getUserInfo() != null || uri.getPort() != -1) throw new IllegalStateException("Unexpected payment provider URL.");
        return value;
    }
    private static ResponseStatusException error(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
