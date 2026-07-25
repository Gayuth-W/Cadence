package com.cadence.flagservice.audit.dto;

import com.cadence.flagservice.audit.domain.AuditAction;
import com.cadence.flagservice.audit.domain.AuditRecord;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditRecordResponse(
        UUID id,
        UUID flagId,
        String flagKey,
        AuditAction action,
        String actor,
        String oldValue,
        String newValue,
        String reason,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public static AuditRecordResponse from(AuditRecord r) {
        return new AuditRecordResponse(r.getId(), r.getFlagId(), r.getFlagKey(), r.getAction(),
                r.getActor(), r.getOldValue(), r.getNewValue(), r.getReason(), r.getMetadata(),
                r.getCreatedAt());
    }
}
