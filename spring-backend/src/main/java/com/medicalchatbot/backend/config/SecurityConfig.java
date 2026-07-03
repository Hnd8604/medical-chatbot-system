package com.medicalchatbot.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            SecurityErrorWriter securityErrorWriter
    ) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                securityErrorWriter.write(
                                        response,
                                        HttpStatus.UNAUTHORIZED.value(),
                                        "UNAUTHORIZED",
                                        "Ban can dang nhap de tiep tuc."
                                )
                        )
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                securityErrorWriter.write(
                                        response,
                                        HttpStatus.FORBIDDEN.value(),
                                        "FORBIDDEN",
                                        "Ban khong co quyen truy cap tai nguyen nay."
                                )
                        )
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/error", "/actuator/health", "/api/health", "/api/chatbot/status").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/refresh",
                                "/api/auth/forgot-password",
                                "/api/auth/verify-reset-code",
                                "/api/auth/reset-password"
                        ).permitAll()
                        .requestMatchers("/api/admin/**", "/api/audit-logs", "/api/metrics/cache").hasRole("ADMIN")
                        .requestMatchers("/api/patients/**").hasAnyRole("DOCTOR", "ADMIN")
                        .requestMatchers(
                                "/api/auth/me",
                                "/api/auth/logout",
                                "/api/auth/change-password",
                                "/api/auth/link-patient",
                                "/api/chat/**",
                                "/api/quota/status",
                                "/api/usage/cost-summary",
                                "/api/notifications/**",
                                "/api/model-pricing",
                                "/api/chat/messages/*/feedback"
                        ).authenticated()
                        .anyRequest().permitAll()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
