package com.cadence.flagservice.audit.repository;

import com.cadence.flagservice.audit.domain.AuditAction;
import com.cadence.flagservice.audit.domain.AuditRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditRecordRepository extends JpaRepository<AuditRecord, UUID> {

        Page<AuditRecord> findByFlagIdOrderByCreatedAtDesc(UUID flagId, Pageable pageable);

        Page<AuditRecord> findByActorOrderByCreatedAtDesc(String actor, Pageable pageable);

        Page<AuditRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);

        /** Every rollback, human or automatic, for the analytics view. */
        @Query("""
                        select a from AuditRecord a
                        where a.flagId = :flagId
                          and a.action in (com.cadence.flagservice.audit.domain.AuditAction.ROLLBACK_FORCED,
                                           com.cadence.flagservice.audit.domain.AuditAction.ROLLBACK_AUTOMATIC)
                        order by a.createdAt desc
                        """)
        List<AuditRecord> findRollbacks(@Param("flagId") UUID flagId);

        List<AuditRecord> findByFlagIdAndActionAndCreatedAtAfterOrderByCreatedAtDesc(
                        UUID flagId, AuditAction action, Instant after);
}
