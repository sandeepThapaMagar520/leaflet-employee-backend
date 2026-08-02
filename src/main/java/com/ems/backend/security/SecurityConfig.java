package com.ems.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiRateLimitFilter apiRateLimitFilter;
    private final CustomUserDetailsService userDetailsService;
    private final ApiErrorWriter errorWriter;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ApiRateLimitFilter apiRateLimitFilter,
            CustomUserDetailsService userDetailsService,
            ApiErrorWriter errorWriter
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.apiRateLimitFilter = apiRateLimitFilter;
        this.userDetailsService = userDetailsService;
        this.errorWriter = errorWriter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(org.springframework.security.config.Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/api/v1/health",
                                "/api/v1/auth/login",
                                "/api/v1/auth/start-account-setup",
                                "/api/v1/auth/verify-email",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/verify-password-otp",
                                "/api/v1/auth/set-password",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/docs"
                        ).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            errorWriter.write(
                                    request,
                                    response,
                                    jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED,
                                    "AUTHENTICATION_REQUIRED",
                                    "Authentication is required."
                            );
                        })
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            errorWriter.write(
                                    request,
                                    response,
                                    jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN,
                                    "ACCESS_DENIED",
                                    "You do not have permission for this action."
                            );
                        }))
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(apiRateLimitFilter, JwtAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
