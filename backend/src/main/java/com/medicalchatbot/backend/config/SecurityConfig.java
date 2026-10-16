package com.medicalchatbot.backend.config;

import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.security.JwtAuthenticationEntryPoint;
import com.medicalchatbot.backend.security.NotificationStreamBearerTokenResolver;
import com.medicalchatbot.backend.security.SecurityErrorWriter;
import com.medicalchatbot.backend.security.UserJwtAuthenticationConverter;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless OAuth2 resource server: requests carry a JWT access token issued by this backend.
 * Paths are relative to the servlet context path ({@code /api/v1}).
 *
 * <p>Two layers of authorization: the URL rules below, where every endpoint must be listed
 * explicitly (anything else is denied, so a new controller cannot become reachable by omission),
 * and {@code @PreAuthorize} on the services.</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/error",
            "/actuator/health",
            "/actuator/health/**",
            "/chatbot/status",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/api-docs",
            "/api-docs/**",
            "/v3/api-docs",
            "/v3/api-docs/**"
    };

    private static final String[] PUBLIC_AUTH_ACTIONS = {
            "/auth/login",
            "/auth/register",
            "/auth/refresh",
            "/auth/password/reset/init",
            "/auth/password/reset/verify-otp",
            "/auth/password/reset/confirm"
    };

    private static final String[] STAFF_ENDPOINTS = {
            "/patients",
            "/patients/**"
    };

    private static final String[] USER_ENDPOINTS = {
            "/auth/logout",
            "/auth/password/change",
            "/users/my-info",
            "/users/my-patient-link",
            "/users/my-patient-profile",
            "/chat",
            "/chat/**",
            "/usage/**",
            "/notifications",
            "/notifications/**"
    };

    JwtDecoder jwtDecoder;
    UserRepository userRepository;
    JwtAuthenticationEntryPoint authenticationEntryPoint;
    SecurityErrorWriter securityErrorWriter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler((request, response, exception) ->
                                securityErrorWriter.write(response, ErrorCode.UNAUTHORIZED))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ACTIONS).permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers(STAFF_ENDPOINTS).hasAnyRole("DOCTOR", "ADMIN")
                        .requestMatchers(USER_ENDPOINTS).authenticated()
                        .anyRequest().denyAll()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(new NotificationStreamBearerTokenResolver())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(new UserJwtAuthenticationConverter(userRepository)))
                );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
