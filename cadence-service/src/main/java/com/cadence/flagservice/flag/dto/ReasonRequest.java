package com.cadence.flagservice.flag.dto;

import jakarta.validation.constraints.NotBlank;

/** Body for pause / resume / rollback. */
public record ReasonRequest(
        @NotBlank(message = "A reason is required") String reason
) {
}
