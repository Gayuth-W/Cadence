package com.cadence.flagservice.rollout.controller;

import com.cadence.flagservice.flag.dto.ReasonRequest;
import com.cadence.flagservice.metrics.model.CanaryResult;
import com.cadence.flagservice.rollout.dto.CreateScheduleRequest;
import com.cadence.flagservice.rollout.dto.ScheduleResponse;
import com.cadence.flagservice.rollout.service.RolloutSchedulerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Staged rollout schedules. As with flags, authorisation lives on the service methods, not here.
 */
@RestController
@RequestMapping("/api/v1/rollouts")
@Tag(name = "Rollout schedules")
public class RolloutScheduleController {

    private final RolloutSchedulerService schedulerService;

    public RolloutScheduleController(RolloutSchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Define a staged rollout. ADMIN. Percentages must strictly increase.")
    public ScheduleResponse create(@Valid @RequestBody CreateScheduleRequest request) {
        return ScheduleResponse.from(schedulerService.create(request.flagId(), request.stages()));
    }

    @GetMapping("/{id}")
    public ScheduleResponse get(@PathVariable UUID id) {
        return ScheduleResponse.from(schedulerService.find(id));
    }

    @GetMapping
    @Operation(summary = "Every schedule ever defined for a flag")
    public List<ScheduleResponse> byFlag(@RequestParam UUID flagId) {
        return schedulerService.findByFlag(flagId).stream().map(ScheduleResponse::from).toList();
    }

    @PostMapping("/{id}/start")
    @Operation(summary = "Enter stage 1. OPERATOR or ADMIN.")
    public ScheduleResponse start(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return ScheduleResponse.from(schedulerService.start(id, request.reason()));
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Hold the schedule and freeze the flag at its current percentage.")
    public ScheduleResponse pause(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return ScheduleResponse.from(schedulerService.pause(id, request.reason()));
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Restart the clock on the current stage.")
    public ScheduleResponse resume(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return ScheduleResponse.from(schedulerService.resume(id, request.reason()));
    }

    /**
     * Skip the remaining hold time. The canary gate still runs, and will still block the advance if the
     * candidate is significantly worse — impatience does not override statistics.
     */
    @PostMapping("/{id}/advance")
    @Operation(summary = "Attempt the next stage immediately. The canary gate still applies.")
    public CanaryResult advance(@PathVariable UUID id) {
        return schedulerService.advanceNow(id);
    }
}
