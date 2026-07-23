package com.cadence.flagservice.security.service;

import com.cadence.flagservice.config.CadenceProperties;
import com.cadence.flagservice.security.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

/** Signs and verifies the stateless access tokens that gate the human control plane. */
@Service
public class JwtService {

    private static final String CLAIM_ROLES = "roles";
    private static final int MIN_SECRET_BYTES = 32;

    private final CadenceProperties properties;
    private SecretKey signingKey;

    public JwtService(CadenceProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void init() {
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "cadence.jwt.secret is not set. Refusing to start: an unsigned or default-signed "
                    + "control plane lets anyone mint an ADMIN token and force a rollback.");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "cadence.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes for HS256; got "
                    + keyBytes.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(String username, Set<Role> roles) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.getJwt().getExpiration());
        return Jwts.builder()
                .subject(username)
                .issuer(properties.getJwt().getIssuer())
                .claim(CLAIM_ROLES, roles.stream().map(Enum::name).toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    public Instant expiryOf(String token) {
        return parse(token).getExpiration().toInstant();
    }

    public String usernameOf(String token) {
        return parse(token).getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> rolesOf(String token) {
        Object raw = parse(token).get(CLAIM_ROLES);
        return raw instanceof List<?> list ? (List<String>) list : List.of();
    }

    /**
     * @throws JwtException on a bad signature, a wrong issuer, or a malformed token
     * @throws ExpiredJwtException when the token has expired
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .requireIssuer(properties.getJwt().getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
