package com.medicalchatbot.backend.config;

import com.medicalchatbot.backend.config.properties.AppProperties;
import com.medicalchatbot.backend.security.AuthRateLimitInterceptor;
import com.medicalchatbot.backend.security.RateLimitInterceptor;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Enabled here as well because MVC test slices do not run the application's properties scan.
@Configuration
@EnableConfigurationProperties(AppProperties.class)
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class WebConfig implements WebMvcConfigurer {

    /**
     * Public auth actions open to abuse by anonymous clients. Token refresh is not listed: its
     * random secret cannot be guessed, and shared IPs refresh often.
     */
    private static final String[] RATE_LIMITED_AUTH_ACTIONS = {
            "/auth/login",
            "/auth/register",
            "/auth/password/reset/init",
            "/auth/password/reset/verify-otp",
            "/auth/password/reset/confirm"
    };

    RateLimitInterceptor rateLimitInterceptor;
    AuthRateLimitInterceptor authRateLimitInterceptor;
    AppProperties appProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/chat");
        registry.addInterceptor(authRateLimitInterceptor).addPathPatterns(RATE_LIMITED_AUTH_ACTIONS);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(appProperties.cors().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
