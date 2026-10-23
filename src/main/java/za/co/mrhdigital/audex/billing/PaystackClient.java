package za.co.mrhdigital.audex.billing;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PaystackClient {
    private final RestClient client;
    private final BillingConfig.Properties config;
    public PaystackClient(RestClient.Builder builder, BillingConfig.Properties config) {
        this.config = config;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        client = builder.baseUrl("https://api.paystack.co").requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + config.secretKey()).build();
        if (config.checkoutEnabled()) {
            if (!config.enabled()) throw new IllegalStateException("Checkout requires billing enforcement.");
            String prefix = config.livePayments() ? "sk_live_" : "sk_test_";
            if (!config.secretKey().startsWith(prefix)) throw new IllegalStateException("Paystack secret key does not match the explicitly selected test/live mode.");
            for (String name : new String[] {"solo-monthly", "solo-annually", "professional-monthly", "professional-annually"}) {
                if (!config.providerPlans().getOrDefault(name, "").startsWith("PLN_")) throw new IllegalStateException("Configure all four Paystack plan codes before enabling checkout.");
            }
            if (config.providerPlans().values().stream().distinct().count() != 4) throw new IllegalStateException("Each plan and billing interval needs its own Paystack plan code.");
        }
    }
    public boolean checkoutEnabled() { return config.enabled() && config.checkoutEnabled(); }
    public JsonNode get(String path) { return call(path, null); }
    public JsonNode post(String path, Map<String, ?> body) { return call(path, body); }
    private JsonNode call(String path, Map<String, ?> body) {
        if (!checkoutEnabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Checkout is not enabled yet.");
        try {
            JsonNode response = body == null ? client.get().uri(path).retrieve().body(JsonNode.class)
                    : client.post().uri(path).body(body).retrieve().body(JsonNode.class);
            if (response == null || !response.path("status").asBoolean() || response.path("data").isMissingNode()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The payment provider could not complete this request. Please retry.");
            }
            return response.path("data");
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The payment provider is temporarily unavailable. Please retry.", e);
        }
    }
}
