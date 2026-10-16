package com.medicalchatbot.backend.config;

import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.security.JwtAuthenticationFilter;
import com.medicalchatbot.backend.security.SecurityErrorWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security. Every endpoint must be listed explicitly: anything else is denied, so a
 * newly added controller cannot become reachable by omission.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/error",
            "/actuator/health",
            "/actuator/health/**",
            "/api/health",
            "/api/chatbot/status",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/api-docs",
            "/api-docs/**",
            "/v3/api-docs",
            "/v3/api-docs/**"
    };

    private static final String[] PUBLIC_AUTH_ACTIONS = {
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/forgot-password",
            "/api/auth/verify-reset-code",
            "/api/auth/reset-password"
    };

    private static final String[] STAFF_ENDPOINTS = {
            "/api/patients",
            "/api/patients/**"
    };

    private static final String[] USER_ENDPOINTS = {
            "/api/auth/me",
            "/api/auth/logout",
            "/api/auth/change-password",
            "/api/auth/link-patient",
            "/api/me/**",
            "/api/chat",
            "/api/chat/**",
            "/api/usage/**",
            "/api/notifications",
            "/api/notifications/**"
    };

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            SecurityErrorWriter securityErrorWriter
    ) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) ->
                                securityErrorWriter.write(response, ErrorCode.AUTHENTICATION_REQUIRED, null))
                        .accessDeniedHandler((request, response, exception) ->
                                securityErrorWriter.write(response, ErrorCode.ACCESS_DENIED, null))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_ACTIONS).permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(STAFF_ENDPOINTS).hasAnyRole("DOCTOR", "ADMIN")
                        .requestMatchers(USER_ENDPOINTS).authenticated()
                        .anyRequest().denyAll()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
