package com.cadence.flagservice.security;

import com.cadence.flagservice.security.domain.ApiKeyScope;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Two filter chains, because there are two genuinely different callers.
 *
 * <ol>
 * <li><b>Data plane</b> ({@code /sdk/v1/**}) — applications, authenticated by a
 * scoped service API
 * key. Authorised by scope, not by role. It can read flag config and write
 * events; nothing else.</li>
 * <li><b>Control plane</b> (everything else) — humans, authenticated by JWT,
 * authorised by role.</li>
 * </ol>
 *
 * <p>
 * Coarse URL rules live here; the actual permission for each mutation lives on
 * the service method
 * itself via {@code @PreAuthorize}. Keeping "who may force a rollback" next to
 * the rollback method,
 * rather than in a URL pattern three files away, is what stops a new endpoint
 * from silently
 * inheriting the wrong access rule.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final ApiKeyAuthenticationFilter apiKeyFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
            ApiKeyAuthenticationFilter apiKeyFilter,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) {
        this.jwtFilter = jwtFilter;
        this.apiKeyFilter = apiKeyFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    /** Data plane. Ordered first so {@code /sdk/**} never reaches the JWT chain. */
    @Bean
    @Order(1)
    public SecurityFilterChain sdkFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/sdk/v1/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/sdk/v1/flags/**")
                        .hasAuthority(ApiKeyScope.FLAGS_READ.authority())
                        .requestMatchers(HttpMethod.POST, "/sdk/v1/evaluate")
                        .hasAuthority(ApiKeyScope.FLAGS_READ.authority())
                        .anyRequest().denyAll())
                .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /** Control plane. */
    @Bean
    @Order(2)
    public SecurityFilterChain managementFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless JWT, no cookies, no session: nothing for CSRF to protect.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Strength 12: human passwords need key-stretching, unlike the 256-bit service
        // API keys.
        return new BCryptPasswordEncoder(12);
    }
}
