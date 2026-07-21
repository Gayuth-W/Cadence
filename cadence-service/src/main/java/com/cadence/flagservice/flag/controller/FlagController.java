package com.cadence.flagservice.flag.controller;

import com.cadence.flagservice.flag.dto.CreateFlagRequest;
import com.cadence.flagservice.flag.dto.FlagResponse;
import com.cadence.flagservice.flag.dto.ReasonRequest;
import com.cadence.flagservice.flag.dto.RolloutRequest;
import com.cadence.flagservice.flag.dto.UpdateFlagRequest;
import com.cadence.flagservice.flag.mapper.FlagMapper;
import com.cadence.flagservice.flag.service.FeatureFlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The human control plane for flags.
 *
 * <p>
 * No {@code @PreAuthorize} here. Authorisation lives on
 * {@link FeatureFlagService}, one layer down,
 * so it cannot be bypassed by a second caller (the scheduler, a future gRPC
 * endpoint) reaching the
 * service directly. The controller's only job is HTTP.
 */
@RestController
@RequestMapping("/api/v1/flags")
@Tag(name = "Flags")
public class FlagController {

    private final FeatureFlagService flagService;
    private final FlagMapper mapper;

    public FlagController(FeatureFlagService flagService, FlagMapper mapper) {
        this.flagService = flagService;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "All flags in this environment")
    public List<FlagResponse> list() {
        return mapper.toResponses(flagService.findAll());
    }

    @GetMapping("/{id}")
    public FlagResponse get(@PathVariable UUID id) {
        return mapper.toResponse(flagService.findById(id));
    }

    @GetMapping("/by-key/{key}")
    public FlagResponse getByKey(@PathVariable String key) {
        return mapper.toResponse(flagService.findByKey(key));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a flag. ADMIN. Always starts OFF at 0%.")
    public FlagResponse create(@Valid @RequestBody CreateFlagRequest request) {
        return mapper.toResponse(flagService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit thresholds, targeting and variant configs. ADMIN.")
    public FlagResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateFlagRequest request) {
        return mapper.toResponse(flagService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a flag. ADMIN. Refused while the flag is live.")
    public void delete(@PathVariable UUID id) {
        flagService.delete(id);
    }

    // ---- Rollout control ----

    @PostMapping("/{id}/rollout")
    @Operation(summary = "Set the rollout percentage. OPERATOR or ADMIN.")
    public FlagResponse rollout(@PathVariable UUID id, @Valid @RequestBody RolloutRequest request) {
        return mapper.toResponse(flagService.setRolloutPercentage(id, request.percentage(), request.reason()));
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Freeze the rollout at its current percentage. OPERATOR or ADMIN.")
    public FlagResponse pause(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return mapper.toResponse(flagService.pause(id, request.reason()));
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Un-pause. OPERATOR or ADMIN.")
    public FlagResponse resume(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return mapper.toResponse(flagService.resume(id, request.reason()));
    }

    @PostMapping("/{id}/rollback")
    @Operation(summary = "Force all traffic back to baseline immediately. ADMIN only.")
    public FlagResponse rollback(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return mapper.toResponse(flagService.rollback(id, request.reason()));
    }

    @PostMapping("/{id}/reset")
    @Operation(summary = "Clear ROLLED_BACK so a fixed candidate can be retried. ADMIN only.")
    public FlagResponse reset(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return mapper.toResponse(flagService.reset(id, request.reason()));
    }
}
