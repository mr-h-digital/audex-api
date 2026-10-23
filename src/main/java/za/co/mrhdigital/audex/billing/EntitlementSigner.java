package za.co.mrhdigital.audex.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public class EntitlementSigner {
    public record Lease(String payload, String signature) {}
    private final PrivateKey key;
    private final ObjectMapper mapper;
    public EntitlementSigner(BillingConfig.Properties config, ObjectMapper mapper) {
        this.mapper = mapper;
        if (!config.enabled()) { key = null; return; }
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            key = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(config.privateKey())));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(config.publicKey())));
            var signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(key); signer.update(new byte[] {1, 2, 3});
            byte[] signature = signer.sign();
            signer.initVerify(publicKey); signer.update(new byte[] {1, 2, 3});
            if (!signer.verify(signature) || signature.length != 64) throw new IllegalArgumentException("Expected a matching P-256 key pair.");
        } catch (Exception e) { throw new IllegalStateException("Billing requires a matching P-256 PKCS8 private key and SPKI public key.", e); }
    }
    public Lease sign(Object claims) {
        if (key == null) throw new IllegalStateException("Billing is disabled.");
        try {
            byte[] payload = mapper.writeValueAsBytes(claims);
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(key); signer.update(payload);
            return new Lease(Base64.getUrlEncoder().withoutPadding().encodeToString(payload),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
        } catch (Exception e) { throw new IllegalStateException("Could not sign offline entitlement.", e); }
    }
}
