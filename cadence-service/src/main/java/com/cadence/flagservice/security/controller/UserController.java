package com.cadence.flagservice.security.controller;

import com.cadence.flagservice.common.ConflictException;
import com.cadence.flagservice.common.NotFoundException;
import com.cadence.flagservice.security.domain.UserAccount;
import com.cadence.flagservice.security.dto.CreateUserRequest;
import com.cadence.flagservice.security.dto.UserResponse;
import com.cadence.flagservice.security.repository.UserAccountRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** User management. ADMIN only — the ability to mint an ADMIN is the ability to force a rollback. */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserAccountRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public List<UserResponse> list() {
        return userRepository.findAll().stream().map(UserResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an operator")
    @Transactional
    public UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ConflictException("Username already exists: " + request.username());
        }
        UserAccount account = new UserAccount(
                request.username(),
                passwordEncoder.encode(request.password()),
                request.roles());
        return UserResponse.from(userRepository.save(account));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@PathVariable UUID id, Authentication authentication) {
        UserAccount account = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("User not found: " + id));

        // Locking yourself out of your own control plane during an incident is a bad afternoon.
        if (account.getUsername().equals(authentication.getName())) {
            throw new ConflictException("You cannot delete the account you are authenticated as");
        }
        userRepository.delete(account);
    }
}
