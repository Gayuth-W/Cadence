package com.cadence.flagservice.security.dto;

import com.cadence.flagservice.security.domain.Role;
import com.cadence.flagservice.security.domain.UserAccount;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UserResponse(UUID id, String username, Set<Role> roles, boolean enabled, Instant createdAt) {

    public static UserResponse from(UserAccount account) {
        return new UserResponse(account.getId(), account.getUsername(), account.getRoles(),
                account.isEnabled(), account.getCreatedAt());
    }
}
