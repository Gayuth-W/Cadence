package com.cadence.flagservice.security.service;

import com.cadence.flagservice.security.domain.Role;
import com.cadence.flagservice.security.domain.UserAccount;
import com.cadence.flagservice.security.repository.UserAccountRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserAccountRepository userRepository;

    public CustomUserDetailsService(UserAccountRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No such user: " + username));

        List<SimpleGrantedAuthority> authorities = account.getRoles().stream()
                .map(Role::authority)
                .map(SimpleGrantedAuthority::new)
                .toList();

        return User.withUsername(account.getUsername())
                .password(account.getPasswordHash())
                .authorities(authorities)
                .disabled(!account.isEnabled())
                .build();
    }
}
