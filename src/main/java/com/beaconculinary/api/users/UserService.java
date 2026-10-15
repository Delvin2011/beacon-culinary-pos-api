package com.beaconculinary.api.users;

import com.beaconculinary.api.auth.AuthService;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@AllArgsConstructor
@Service
public class UserService {
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    public Iterable<UserDto> getAllUsers(String sortBy) {
        if (!Set.of("name", "email").contains(sortBy))
            sortBy = "name";

        return userRepository.findAll(Sort.by(sortBy))
                .stream()//to map user objects to user Dto
                .map(userMapper::toDto)//:: similar to using lambda function, e.g user =>
                .toList();
    }

    public UserDto getUser(Long userId) {
        var user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        return userMapper.toDto(user);
    }

    /** Public, pre-login list for the POS login screen's cashier tile grid. */
    /** The active users who log in by PIN on the given screen. The kitchen screen lists only users
     * with a PIN set (the Head Chef's PIN is optional); the POS list is unchanged. */
    public List<CashierSummaryDto> getLoginTiles(LoginScreen screen) {
        return userRepository.findByActiveTrueAndRoleIn(screen.roles())
                .stream()
                .filter(user -> !screen.requiresPin() || user.getPinHash() != null)
                .map(userMapper::toCashierSummaryDto)
                .toList();
    }

    public UserDto registerUser(RegisterUserRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateUserException();
        }

        var user = userMapper.toEntity(request);
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setRole(Role.USER);
        userRepository.save(user);

        return userMapper.toDto(user);
    }

    public UserDto updateUser(Long userId, UpdateUserRequest request) {
        var user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        userMapper.update(request, user);
        userRepository.save(user);

        return userMapper.toDto(user);
    }

    public void deleteUser(Long userId) {
        var user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        userRepository.delete(user);
    }

    /** Changes the caller's own password. Admins reset other people's through /admin/staff. */
    public void changePassword(Long userId, ChangePasswordRequest request) {
        var currentUser = authService.getCurrentUser();
        if (!currentUser.getId().equals(userId)) {
            throw new NotOwnAccountException("You can only change your own password.");
        }
        var user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);

        if (user.getPassword() == null || request.getOldPassword() == null
                || !passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new AccessDeniedException("Password does not match");
        }
        var newPassword = request.getNewPassword();
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 25) {
            throw new InvalidPasswordException("Password must be between 6 and 25 characters.");
        }

        // Previously stored as plain text, which left the user unable to log in again.
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}