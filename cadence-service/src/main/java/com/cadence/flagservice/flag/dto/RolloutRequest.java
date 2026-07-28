package com.cadence.flagservice.flag.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RolloutRequest(
        @Min(0) @Max(100) int percentage,
        /* Required. An unexplained percentage change in a production rollout is an unanswerable
           question during the post-mortem, so the API refuses to accept one. */
        @NotBlank(message = "A reason is required for every rollout change") String reason
) {
}
