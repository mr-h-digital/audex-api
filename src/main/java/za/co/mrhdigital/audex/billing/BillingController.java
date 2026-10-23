package za.co.mrhdigital.audex.billing;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import za.co.mrhdigital.audex.auth.AuthInterceptor;
import za.co.mrhdigital.audex.auth.AuthService;
import za.co.mrhdigital.audex.auth.AuthUser;

@RestController
@RequestMapping("/api/billing")
public class BillingController {
    public record Choice(String plan, String cadence) {}
    public record Subscription(String code) {}
    public record Grant(UUID userId, String reason) {}
    public record Account(UUID userId) {}
    private final EntitlementService entitlements;
    private final EntitlementSigner signer;
    private final BillingService billing;
    private final BillingConfig.Properties config;
    private final AuthService auth;
    private final BillingEvents events;
    private final PhotoUsageReconciler photoUsage;
    public BillingController(EntitlementService entitlements, EntitlementSigner signer, BillingService billing,
                             BillingConfig.Properties config, AuthService auth, BillingEvents events, PhotoUsageReconciler photoUsage) {
        this.entitlements = entitlements; this.signer = signer; this.billing = billing;
        this.config = config; this.auth = auth; this.events = events;
        this.photoUsage = photoUsage;
    }
    @GetMapping
    public Map<String, Object> current(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user) {
        if (!entitlements.enabled()) return Map.of("enabled", false);
        return Map.of("enabled", true, "testMode", !config.livePayments(), "checkoutEnabled", config.checkoutEnabled(),
                "lease", signer.sign(entitlements.access(user.id())), "subscriptions", billing.subscriptions(user.id()));
    }
    @PostMapping("/verification")
    public Map<String, String> verify(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user) {
        auth.sendVerification(user.id());
        return Map.of("message", "A verification link has been emailed to your account address.");
    }
    @PostMapping("/checkout")
    public BillingService.Checkout checkout(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user, @RequestBody Choice choice) {
        return billing.checkout(user.id(), choice.plan(), choice.cadence());
    }
    @PostMapping("/refresh")
    public Map<String, String> refresh(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user) {
        billing.refresh(user.id());
        return Map.of("message", "Payments checked. Subscription updates can take a minute to arrive.");
    }
    @PostMapping("/cancel")
    public Map<String, String> cancel(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user, @RequestBody Subscription body) {
        billing.cancel(user.id(), body.code());
        return Map.of("message", "Renewal cancelled. Your verified paid access remains until its expiry.");
    }
    @PostMapping("/manage")
    public Map<String, String> manage(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user, @RequestBody Subscription body) {
        return Map.of("url", billing.manage(user.id(), body.code()));
    }
    @PostMapping("/admin/grants")
    public Map<String, String> grant(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user, @RequestBody Grant body) {
        entitlements.requireAdmin(user.id());
        entitlements.grant(body.userId(), "admin:" + user.id(), body.reason());
        return Map.of("message", "Verified existing candidate granted complimentary access.");
    }
    @PostMapping("/webhook")
    public Map<String, Boolean> webhook(@RequestBody byte[] body, @RequestHeader(value = "x-paystack-signature", required = false) String signature) {
        events.receive(body, signature);
        return Map.of("received", true);
    }
    @PostMapping("/admin/photo-sizes")
    public PhotoUsageReconciler.Result photoSizes(@RequestAttribute(AuthInterceptor.ATTRIBUTE) AuthUser user, @RequestBody Account account) {
        return photoUsage.reconcile(user.id(), account.userId());
    }
}
