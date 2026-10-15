package za.co.mrhdigital.audex.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Lets the AudEx web app (served from its own address) call the API. Set AUDEX_ALLOWED_ORIGINS in production. */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private final String[] origins;

    public CorsConfig(@Value("${audex.allowed-origins:http://localhost:5173}") String origins) {
        this.origins = origins.split(",");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET", "POST", "PUT", "PATCH", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type");
    }
}
