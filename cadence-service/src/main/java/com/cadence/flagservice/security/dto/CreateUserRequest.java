package com.cadence.flagservice.security.dto;

import com.cadence.flagservice.security.domain.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record CreateUserRequest(
        @NotBlank @Size(min = 3, max = 100) String username,
        @NotBlank @Size(min = 12, message = "Control-plane passwords must be at least 12 characters")
        String password,
        @NotEmpty Set<Role> roles
) {
}
