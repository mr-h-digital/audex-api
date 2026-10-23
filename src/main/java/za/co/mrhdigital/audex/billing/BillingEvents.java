package za.co.mrhdigital.audex.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import za.co.mrhdigital.audex.auth.Tokens;

@Service
public class BillingEvents {
    private static final Logger log = LoggerFactory.getLogger(BillingEvents.class);
    private static final List<String> SUPPORTED = List.of("charge.success", "subscription.create", "subscription.disable",
            "subscription.not_renew", "invoice.update", "refund.processed", "charge.dispute.create");
    private final JdbcTemplate jdbc;
    private final BillingConfig.Properties config;
    private final ObjectMapper mapper;
    private final BillingService billing;
    private final TransactionTemplate transaction;
    public BillingEvents(JdbcTemplate jdbc, BillingConfig.Properties config, ObjectMapper mapper,
                         BillingService billing, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.config = config; this.mapper = mapper; this.billing = billing;
        transaction = new TransactionTemplate(manager);
    }
    public void receive(byte[] body, String signature) {
        if (!config.enabled() || !config.checkoutEnabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Billing webhooks are not enabled.");
        if (body.length > 256_000) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Webhook too large.");
        if (!validSignature(body, signature, config.secretKey())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature.");
        JsonNode payload;
        try { payload = mapper.readTree(body); }
        catch (java.io.IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid webhook JSON.", e); }
        if (payload == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing webhook JSON.");
        String type = payload.path("event").asText();
        if (!SUPPORTED.contains(type)) { log.debug("Ignoring unsupported Paystack event {}", type); return; }
        JsonNode data = payload.path("data");
        String ref = null, code = null;
        if (type.startsWith("subscription.")) code = BillingService.segment(data.path("subscription_code").asText());
        else if (type.equals("invoice.update")) {
            if (!data.path("paid").asBoolean()) return;
            ref = BillingService.segment(data.path("transaction").path("reference").asText());
            code = BillingService.segment(data.path("subscription").path("subscription_code").asText());
        }
        else if (type.equals("refund.processed") || type.equals("charge.dispute.create")) ref = BillingService.segment(data.path("transaction").path("reference").asText());
        else ref = BillingService.segment(data.path("reference").asText());
        // Store only routing identifiers, not card authorisations, customer details or the raw webhook.
        String id = Tokens.hash(type + ":" + ref + ":" + code);
        jdbc.update("INSERT INTO billing_event(id, event_type, reference, subscription_code) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                id, type, ref, code);
    }
    public static boolean validSignature(byte[] body, String signature, String secret) {
        if (secret == null || secret.isBlank() || signature == null || !signature.matches("[a-fA-F0-9]{128}")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("HMAC-SHA512 unavailable.", e); }
    }
    @Scheduled(fixedDelay = 60_000, initialDelayString = "${audex.billing.reconcile-initial-delay:15000}")
    public void reconcile() {
        if (!config.enabled() || !config.checkoutEnabled()) return;
        var events = jdbc.queryForList("""
                SELECT id FROM billing_event WHERE processed_at IS NULL
                ORDER BY attempts, received_at LIMIT 25
                """);
        for (Map<String, Object> row : events) {
            String id = (String) row.get("id");
            try {
                transaction.executeWithoutResult(status -> {
                    var locked = jdbc.queryForList("SELECT * FROM billing_event WHERE id = ? AND processed_at IS NULL FOR UPDATE SKIP LOCKED", id);
                    if (locked.isEmpty()) return;
                    var event = locked.getFirst();
                    billing.process((String) event.get("event_type"), (String) event.get("reference"), (String) event.get("subscription_code"));
                    jdbc.update("UPDATE billing_event SET processed_at = now(), attempts = attempts + 1, last_error = NULL WHERE id = ?", id);
                });
            } catch (RuntimeException e) {
                // Keep durable work pending. An earlier event may still be needed to establish ownership.
                String message = e instanceof ResponseStatusException r ? r.getReason()
                        : e instanceof IllegalStateException ? e.getMessage() : e.getClass().getSimpleName();
                if (message == null) message = e.getClass().getSimpleName();
                message = message.substring(0, Math.min(message.length(), 500));
                jdbc.update("UPDATE billing_event SET attempts = attempts + 1, last_error = ? WHERE id = ?", message, id);
                log.warn("Billing event {} remains pending: {}", id, message);
            }
        }
    }
}
