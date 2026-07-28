package com.cadence.flagservice.security;

import com.cadence.flagservice.security.service.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Extracts and validates the operator's bearer token, populating the {@link SecurityContextHolder}
 * with the authenticated principal and their roles.
 *
 * <p>The principal set here is what {@code FlagAuditService} later stamps onto every audit record.
 * That is the whole reason this filter exists rather than a simple API key on the console too:
 * "SYSTEM forced a rollback" is a useless audit line, "gayuth forced a rollback at 22:14" is not.
 *
 * <p>A missing or invalid token is not an error here — the filter simply leaves the context
 * unauthenticated and lets the {@link RestAuthenticationEntryPoint} decide, so that public endpoints
 * (login, health, docs) still work.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {

        String token = extractToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                io.jsonwebtoken.Claims claims = jwtService.parse(token);
                String username = claims.getSubject();
                
                @SuppressWarnings("unchecked")
                List<String> rolesClaim = (List<String>) claims.get("roles");
                
                List<SimpleGrantedAuthority> authorities = rolesClaim.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList();

                var authentication = new UsernamePasswordAuthenticationToken(username, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                // Expired, forged, or malformed. Leave the context empty; the entry point returns 401.
                log.debug("Rejected JWT on {}: {}", request.getRequestURI(), e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
