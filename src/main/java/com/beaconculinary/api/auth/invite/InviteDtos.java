package com.beaconculinary.api.auth.invite;

import com.beaconculinary.api.staff.StaffRolePolicy.Requirement;
import com.beaconculinary.api.users.Role;

import java.time.Instant;

/** T0-6a: request and response bodies of the public invite endpoints. */
public final class InviteDtos {
    private InviteDtos() {
    }

    /** POST /auth/invites/lookup. toString never shows the token. */
    public record TokenRequest(String token) {
        @Override
        public String toString() {
            return "TokenRequest[token=<hidden>]";
        }
    }

    /** POST /auth/invites/accept. toString never shows the token, password or PIN. */
    public record AcceptRequest(String token, String password, String pin) {
        @Override
        public String toString() {
            return "AcceptRequest[token=<hidden>, password=<hidden>, pin=<hidden>]";
        }
    }

    /** How the person will sign in afterwards. */
    public enum SignInWith { PASSWORD, PIN }

    /**
     * What the set-up screen shows. password / pin: REQUIRED (must be set), OPTIONAL (may be set) or
     * NONE (not part of this link). email only when they will sign in with a password.
     */
    public record LookupResponse(String userName, Role role, String roleLabel, InvitePurpose purpose,
                                 Requirement password, Requirement pin, SignInWith signInWith, String email,
                                 Instant expiresAt) {
    }

    /** After accepting: nobody is signed in; the screen sends the person to the right sign-in. */
    public record AcceptResponse(SignInWith signInWith, String email) {
    }
}
