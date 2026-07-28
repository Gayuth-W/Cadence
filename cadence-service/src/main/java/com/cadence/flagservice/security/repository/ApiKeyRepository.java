package com.cadence.flagservice.security.repository;

import com.cadence.flagservice.security.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyPrefixAndActiveTrue(String keyPrefix);

    List<ApiKey> findAllByEnvironment(String environment);
}
