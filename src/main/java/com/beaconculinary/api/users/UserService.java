package com.beaconculinary.api.users;

import com.beaconculinary.api.auth.AuthService;
import com.beaconculinary.api.auth.DemoAccountGuard;
import com.beaconculinary.api.auth.protection.LoginAttemptService;
import com.beaconculinary.api.auth.protection.LoginMethod;
import com.beaconculinary.api.auth.protection.LoginOutcome;
import com.beaconculinary.api.devices.DeviceGate;
import lombok.AllArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@AllArgsConstructor
@Service
public class UserService {
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final LoginAttemptService loginAttempts;
    private final DeviceGate deviceGate;
    private final DemoAccountGuard demoGuard;

    /** Public, pre-login list for the POS login screen's cashier tile grid. */
    /** The active users who log in by PIN on the given screen. The kitchen screen lists only users
     * with a PIN set (the Head Chef's PIN is optional); the POS list is unchanged. */
    public List<CashierSummaryDto> getLoginTiles(LoginScreen screen) {
        // WS1-1b: the POS list (also GET /users/cashiers) needs a registered POS till; in required
        // mode admins are left out of the KITCHEN and BAR lists, because their PIN sign-in needs one.
        if (screen == LoginScreen.POS) {
            deviceGate.requireRegisteredTill("POS login tiles");
        }
        var hideAdmins = screen != LoginScreen.POS && deviceGate.hidesAdminsOutsidePos();
        return userRepository.findByActiveTrueAndRoleIn(screen.roles())
                .stream()
                .filter(user -> !screen.requiresPin() || user.getPinHash() != null)
                .filter(user -> !hideAdmins || user.getRole() != Role.ADMIN)
                // T0-6a: demo accounts are left off while the demo guard is on.
                .filter(user -> !demoGuard.refuses(user))
                .map(userMapper::toCashierSummaryDto)
                .toList();
    }

    /** Changes the caller's own password. Admins reset other people's through /admin/staff. */
    public void changePassword(Long userId, ChangePasswordRequest request) {
        var currentUser = authService.getCurrentUser();
        if (!currentUser.getId().equals(userId)) {
            throw new NotOwnAccountException("You can only change your own password.");
        }
        var user = userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
        var attempt = loginAttempts.begin(LoginMethod.CHANGE_PASSWORD, userId.toString(), userId);
        if (demoGuard.refuses(user)) {
            loginAttempts.finish(attempt, LoginOutcome.DEMO_ACCOUNT, userId);
            demoGuard.alertRefused("CHANGE_PASSWORD", userId);
            throw new AccessDeniedException("Password does not match");
        }

        if (user.getPassword() == null || request.getOldPassword() == null
                || !passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            loginAttempts.finish(attempt, LoginOutcome.BAD_CREDENTIAL, userId);
            throw new AccessDeniedException("Password does not match");
        }
        loginAttempts.finish(attempt, LoginOutcome.SUCCESS, userId);
        var newPassword = request.getNewPassword();
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 25) {
            throw new InvalidPasswordException("Password must be between 6 and 25 characters.");
        }

        // Previously stored as plain text, which left the user unable to log in again.
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
    }
}