package za.co.mrhdigital.audex.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Switch off only in tests with audex.auth.enabled=false. */
@Configuration
@ConditionalOnProperty(name = "audex.auth.enabled", havingValue = "true", matchIfMissing = true)
public class AuthWebConfig implements WebMvcConfigurer {
    private final AuthService auth;

    public AuthWebConfig(AuthService auth) { this.auth = auth; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(auth))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/health", "/api/billing/webhook");
    }
}
