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

        List<AuditRecord> findByFlagIdAndActionAndCreatedAtAfterOrderByCreatedAtDesc(
                        UUID flagId, AuditAction action, Instant after);
}
