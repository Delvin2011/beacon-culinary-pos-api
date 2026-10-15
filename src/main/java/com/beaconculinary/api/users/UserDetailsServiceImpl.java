package com.beaconculinary.api.users;

import lombok.AllArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@AllArgsConstructor
@Service
public class UserDetailsServiceImpl implements UserDetailsService {
    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        var user = userRepository.findByEmail(email).orElseThrow(
                () -> new UsernameNotFoundException("User not found"));

        // A deactivated user is disabled, so email/password login is refused (PIN login already
        // checks active). A user without a password (PIN-only staff) can't log in by email.
        if (user.getPassword() == null) {
            throw new UsernameNotFoundException("User not found");
        }
        return new User(
            user.getEmail(),
            user.getPassword(),
            user.isActive(), true, true, true,
            Collections.emptyList()
        );
    }
}
