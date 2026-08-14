package com.rcf.imas.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            JwtAuthFilter jwtAuthFilter
    ) throws Exception {

        http
            .csrf(csrf -> csrf.disable())

            .cors(cors -> {})

            
            .sessionManagement(s ->
                s.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            .authorizeHttpRequests(auth -> auth

                // Allow CORS preflight requests
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // Authentication
                .requestMatchers(
                    HttpMethod.POST,
                    "/api/auth/login",
                    "/api/auth/authorize-role"
                ).permitAll()

                .requestMatchers(
                    HttpMethod.GET,
                    "/api/exams/hallticket/**"
                ).permitAll()

                .requestMatchers(
                    HttpMethod.GET,
                    "/api/student",
                    "/api/student/"
                ).permitAll()

                .requestMatchers(
                    HttpMethod.GET,
                    "/api/coordinator",
                    "/api/coordinator/"
                ).permitAll()

                .requestMatchers(
                    HttpMethod.GET,
                    "/uploads/events/photos/**",
                    "/uploads/events/reports/**"
                ).permitAll()

                .requestMatchers("/actuator/health").permitAll()

                .anyRequest().authenticated()
            )

            .exceptionHandling(e ->
                e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write(
                        "{\"error\":\"No token provided\",\"code\":\"TOKEN_MISSING\"}"
                    );
                })
            )

            .addFilterBefore(
                jwtAuthFilter,
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(
            BCryptPasswordEncoder.BCryptVersion.$2B,
            10
        );
    }
}