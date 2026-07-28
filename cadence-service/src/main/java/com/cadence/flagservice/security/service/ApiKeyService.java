package com.cadence.flagservice.security.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cadence.flagservice.common.NotFoundException;
import com.cadence.flagservice.security.domain.ApiKey;
import com.cadence.flagservice.security.domain.ApiKeyScope;
import com.cadence.flagservice.security.repository.ApiKeyRepository;

/** Mints, hashes and verifies the service API keys used by the SDK data plane. */
@Service
public class ApiKeyService {

    private static final String PREFIX = "cad_";
    private static final int PREFIX_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /** The plaintext half of a freshly minted key. Shown once, then unrecoverable. */
    public record IssuedKey(ApiKey record, String plaintext) {
    }

    @Transactional
    public IssuedKey issue(String name, String environment, Set<ApiKeyScope> scopes, String createdBy) {
        byte[] entropy = new byte[32];
        RANDOM.nextBytes(entropy);
        String random = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        String plaintext = PREFIX + random;                       // e.g. cad_AbC9dEf...
        String prefix = plaintext.substring(0, PREFIX_LENGTH);    // "cad_" + 8 random chars
        ApiKey key = new ApiKey(name, environment, prefix, sha256(plaintext), scopes, createdBy);
        return new IssuedKey(apiKeyRepository.save(key), plaintext);
    }

    /**
     * Verify a presented key.
     *
     * <p>SHA-256 rather than BCrypt: this runs on every SDK request, and a deliberately slow KDF on a
     * high-throughput data-plane path is a self-inflicted denial of service. The trade-off is sound
     * because the key is 256 bits of {@link SecureRandom} entropy, not a human-chosen password — there
     * is no dictionary to attack, so key-stretching buys nothing.
     *
     * <p>Comparison is constant-time to keep the hash from leaking through timing.
     */
    @Transactional
    public Optional<ApiKey> verify(String presented) {
        if (presented == null || presented.length() < PREFIX_LENGTH) {
            return Optional.empty();
        }
        String prefix = presented.substring(0, PREFIX_LENGTH);
        Optional<ApiKey> candidate = apiKeyRepository.findByKeyPrefixAndActiveTrue(prefix);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        ApiKey key = candidate.get();
        boolean matches = MessageDigest.isEqual(
                sha256(presented).getBytes(StandardCharsets.UTF_8),
                key.getKeyHash().getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            return Optional.empty();
        }
        // Touch at most once a minute: this is an audit convenience, not a reason to write to Postgres
        // on every single flag-config fetch.
        Instant last = key.getLastUsedAt();
        if (last == null || last.isBefore(Instant.now().minus(1, ChronoUnit.MINUTES))) {
            key.setLastUsedAt(Instant.now());
        }
        return Optional.of(key);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(String environment) {
        return apiKeyRepository.findAllByEnvironment(environment);
    }

    @Transactional
    public void revoke(UUID id) {
        ApiKey key = apiKeyRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("API key not found: " + id));
        // Revoke, never delete: the audit trail references keys, and a deleted key is an unanswerable
        // question six months later.
        key.setActive(false);
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
