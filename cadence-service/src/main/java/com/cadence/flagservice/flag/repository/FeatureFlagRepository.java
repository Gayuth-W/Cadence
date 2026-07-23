package com.cadence.flagservice.flag.repository;

import com.cadence.core.model.FlagState;
import com.cadence.flagservice.flag.domain.FeatureFlag;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, UUID> {

    Optional<FeatureFlag> findByKeyAndEnvironment(String key, String environment);

    boolean existsByKeyAndEnvironment(String key, String environment);

    List<FeatureFlag> findAllByEnvironment(String environment);

    List<FeatureFlag> findAllByEnvironmentAndStateIn(String environment, Collection<FlagState> states);

    /**
     * Used by the rollback watcher and the stage scheduler. {@code OPTIMISTIC_FORCE_INCREMENT} makes the
     * platform's own write bump the version even when it only reads, so a human editing the same flag
     * mid-rollback is guaranteed to see a 409 rather than quietly overwrite the withdrawal.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("select f from FeatureFlag f where f.id = :id")
    Optional<FeatureFlag> findByIdForUpdate(@Param("id") UUID id);
}
