package za.co.mrhdigital.audex.billing;

import java.time.Clock;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(BillingConfig.Properties.class)
public class BillingConfig {
    @Bean Clock billingClock() { return Clock.systemUTC(); }

    @ConfigurationProperties("audex.billing")
    public record Properties(boolean enabled, boolean checkoutEnabled, boolean livePayments,
                             String secretKey, String privateKey, String publicKey,
                             String bootstrapAdminId, Map<String, String> providerPlans) {
        public Properties {
            secretKey = secretKey == null ? "" : secretKey;
            privateKey = privateKey == null ? "" : privateKey;
            publicKey = publicKey == null ? "" : publicKey;
            bootstrapAdminId = bootstrapAdminId == null ? "" : bootstrapAdminId;
            providerPlans = providerPlans == null ? Map.of() : Map.copyOf(providerPlans);
        }
    }
}
