package com.cadence.flagservice.audit.service;

import com.cadence.flagservice.audit.domain.AuditAction;
import com.cadence.flagservice.audit.domain.AuditRecord;
import com.cadence.flagservice.audit.repository.AuditRecordRepository;
import com.cadence.flagservice.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * The single door through which every flag mutation must pass.
 *
 * <p>Two design choices worth defending:
 *
 * <p><b>The actor is never a parameter.</b> It is resolved from the security context by
 * {@link CurrentActor}. If callers could pass an actor string, the audit trail would record whatever
 * the caller claimed, and a bug (or an attacker) could attribute a forced rollback to someone else.
 * The one thing an audit log must never do is lie about who acted.
 *
 * <p><b>Audit writes join the caller's transaction</b> ({@link Propagation#REQUIRED}). If the flag
 * update rolls back, so does its audit line — otherwise the log would accumulate records for changes
 * that never happened. The converse also holds: an audit write that fails aborts the mutation. That is
 * intentional. An unauditable change to a production rollout should not be allowed to succeed.
 */
@Service
public class FlagAuditService {

    private static final Logger log = LoggerFactory.getLogger(FlagAuditService.class);

    private final AuditRecordRepository repository;
    private final CurrentActor currentActor;

    public FlagAuditService(AuditRecordRepository repository, CurrentActor currentActor) {
        this.repository = repository;
        this.currentActor = currentActor;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AuditRecord record(AuditAction action, UUID flagId, String flagKey,
                              String oldValue, String newValue, String reason) {
        return record(action, flagId, flagKey, oldValue, newValue, reason, Map.of());
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public AuditRecord record(AuditAction action, UUID flagId, String flagKey,
                              String oldValue, String newValue, String reason,
                              Map<String, Object> metadata) {
        String actor = currentActor.name();
        AuditRecord entry = AuditRecord.builder(action, actor)
                .flag(flagId, flagKey)
                .oldValue(oldValue)
                .newValue(newValue)
                .reason(reason)
                .metadata(metadata)
                .build();

        AuditRecord saved = repository.save(entry);
        log.info("AUDIT actor={} action={} flag={} reason={}", actor, action, flagKey, reason);
        return saved;
    }

    /**
     * Explicit SYSTEM attribution for actions taken by the platform's own automation, which runs on a
     * scheduler thread with no security context. {@link CurrentActor} would already return SYSTEM there;
     * calling this method makes the intent unmissable at the call site.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditRecord recordSystem(AuditAction action, UUID flagId, String flagKey,
                                    String oldValue, String newValue, String reason,
                                    Map<String, Object> metadata) {
        AuditRecord entry = AuditRecord.builder(action, CurrentActor.SYSTEM)
                .flag(flagId, flagKey)
                .oldValue(oldValue)
                .newValue(newValue)
                .reason(reason)
                .metadata(metadata)
                .build();
        AuditRecord saved = repository.save(entry);
        log.warn("AUDIT actor=SYSTEM action={} flag={} reason={} evidence={}", action, flagKey, reason, metadata);
        return saved;
    }
}
