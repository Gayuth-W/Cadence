package com.cadence.flagservice.audit.controller;

import com.cadence.flagservice.audit.dto.AuditRecordResponse;
import com.cadence.flagservice.audit.repository.AuditRecordRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The audit log is readable by every role including VIEWER, and writable by none.
 * There is no POST, PUT or DELETE here, by construction.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit")
@PreAuthorize("hasAnyRole('VIEWER','OPERATOR','ADMIN')")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditRecordRepository repository;

    public AuditController(AuditRecordRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "The full trail, newest first")
    public Page<AuditRecordResponse> list(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size,
                                          @RequestParam(required = false) String actor) {
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        Page<com.cadence.flagservice.audit.domain.AuditRecord> results = actor == null
                ? repository.findAllByOrderByCreatedAtDesc(pageable)
                : repository.findByActorOrderByCreatedAtDesc(actor, pageable);
        return results.map(AuditRecordResponse::from);
    }

    @GetMapping("/flags/{flagId}")
    @Operation(summary = "Everything that ever happened to one flag")
    public Page<AuditRecordResponse> forFlag(@PathVariable UUID flagId,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return repository.findByFlagIdOrderByCreatedAtDesc(flagId, PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE)))
                .map(AuditRecordResponse::from);
    }

    @GetMapping("/flags/{flagId}/rollbacks")
    @Operation(summary = "Every rollback of this flag, with the metric evidence that triggered each one")
    public List<AuditRecordResponse> rollbacks(@PathVariable UUID flagId) {
        return repository.findRollbacks(flagId).stream().map(AuditRecordResponse::from).toList();
    }
}
