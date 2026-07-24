package com.cadence.flagservice.security.controller;

import com.cadence.flagservice.security.domain.Role;
import com.cadence.flagservice.security.domain.UserAccount;
import com.cadence.flagservice.security.dto.LoginRequest;
import com.cadence.flagservice.security.dto.LoginResponse;
import com.cadence.flagservice.security.dto.UserResponse;
import com.cadence.flagservice.security.repository.UserAccountRepository;
import com.cadence.flagservice.security.service.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserAccountRepository userRepository;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtService jwtService,
                          UserAccountRepository userRepository) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    private final java.util.Map<String, RateLimitBucket> rateLimiters = new java.util.concurrent.ConcurrentHashMap<>();

    private static class RateLimitBucket {
        int tokens = 5;
        long lastRefillTime = System.currentTimeMillis();

        synchronized boolean tryConsume() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            int refill = (int) (elapsed / 10000); // 1 token every 10 seconds
            if (refill > 0) {
                tokens = Math.min(5, tokens + refill);
                lastRefillTime = now;
            }
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }
    }

    @PostMapping("/login")
    @Operation(summary = "Exchange username + password for a bearer token")
    public LoginResponse login(jakarta.servlet.http.HttpServletRequest httpRequest, @Valid @RequestBody LoginRequest request) {
        String clientIp = httpRequest.getRemoteAddr();
        RateLimitBucket bucket = rateLimiters.computeIfAbsent(clientIp, k -> new RateLimitBucket());
        if (!bucket.tryConsume()) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded"
            );
        }

        // Throws BadCredentialsException on failure, translated to a 401 by GlobalExceptionHandler.
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));

        UserAccount account = userRepository.findByUsername(auth.getName()).orElseThrow();
        String token = jwtService.generateToken(account.getUsername(), account.getRoles());

        log.info("Operator '{}' authenticated with roles {}", account.getUsername(), account.getRoles());

        return new LoginResponse(
                token,
                "Bearer",
                jwtService.expiryOf(token),
                account.getUsername(),
                account.getRoles().stream().map(Role::name).collect(Collectors.toSet()));
    }

    @GetMapping("/me")
    @Operation(summary = "The identity behind the presented token")
    public ResponseEntity<UserResponse> me(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
                .map(UserResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Exposed for the dashboard's role-gating, so it can hide controls the token cannot use anyway. */
    @GetMapping("/roles")
    @Operation(summary = "Roles carried by the presented token")
    public Set<String> roles(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(a -> a.getAuthority().replaceFirst("^ROLE_", ""))
                .collect(Collectors.toSet());
    }
}
