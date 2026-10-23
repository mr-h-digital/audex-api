package za.co.mrhdigital.audex.billing;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class BillingCoreTest {
    @Test void pricesAndCapacitiesAreServerAuthoritative() {
        assertEquals(19_900, Plan.SOLO.amount("monthly"));
        assertEquals(199_000, Plan.SOLO.amount("annually"));
        assertEquals(39_900, Plan.PROFESSIONAL.amount("monthly"));
        assertEquals(399_000, Plan.PROFESSIONAL.amount("annually"));
        assertEquals(100_000_000, Plan.TRIAL.storageBytes);
        assertEquals(5_000_000_000L, Plan.PROFESSIONAL.storageBytes);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> Plan.paid("COMPLIMENTARY"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> Plan.SOLO.amount("weekly"));
    }
    @Test void signaturesCoverExactWebhookBytesAndRejectMalformedOrWrongKeys() throws Exception {
        byte[] body = "{\"event\":\"charge.success\"}".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec("fixture-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        String signature = HexFormat.of().formatHex(mac.doFinal(body));
        assertTrue(BillingEvents.validSignature(body, signature, "fixture-secret"));
        assertFalse(BillingEvents.validSignature((new String(body, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8), signature, "fixture-secret"));
        assertFalse(BillingEvents.validSignature(body, signature, "wrong"));
        assertFalse(BillingEvents.validSignature(body, "invalid", "fixture-secret"));
        assertFalse(BillingEvents.validSignature(body, null, "fixture-secret"));
        assertFalse(BillingEvents.validSignature(body, signature, ""));
    }
    @Test void leasesUseBrowserCompatibleP256Signatures() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1"));
        var keys = generator.generateKeyPair();
        var config = new BillingConfig.Properties(true, false, false, "",
                Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()),
                Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()), "", Map.of());
        var signer = new EntitlementSigner(config, new ObjectMapper());
        var lease = signer.sign(Map.of("accountId", "fixture", "plan", "SOLO"));
        var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(keys.getPublic());
        verifier.update(Base64.getUrlDecoder().decode(lease.payload()));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(lease.signature())));
        assertEquals(64, Base64.getUrlDecoder().decode(lease.signature()).length);
    }
    @Test void periodsFollowCalendarIntervalsAndPaystackMonthEndPolicy() {
        assertEquals(Instant.parse("2026-11-23T12:00:00Z"), BillingService.periodEnd(Instant.parse("2026-10-23T12:00:00Z"), "monthly"));
        assertEquals(Instant.parse("2026-11-28T12:00:00Z"), BillingService.periodEnd(Instant.parse("2026-10-31T12:00:00Z"), "monthly"));
        assertEquals(Instant.parse("2025-02-28T12:00:00Z"), BillingService.periodEnd(Instant.parse("2024-02-29T12:00:00Z"), "annually"));
    }
}
