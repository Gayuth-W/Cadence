package com.cadence.flagservice.metrics.repository;

import com.cadence.flagservice.metrics.domain.MetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, UUID> {

    List<MetricSnapshot> findByFlagIdAndCapturedAtBetweenOrderByCapturedAtAsc(
            UUID flagId, Instant from, Instant to);

    List<MetricSnapshot> findByFlagIdAndVariantKeyOrderByCapturedAtDesc(UUID flagId, String variantKey);

    /** The last stable reading, used to build the "diff against last stable snapshot" in a rollback alert. */
    MetricSnapshot findFirstByFlagIdAndVariantKeyOrderByCapturedAtDesc(UUID flagId, String variantKey);

    void deleteByCapturedAtBefore(Instant cutoff);
}
