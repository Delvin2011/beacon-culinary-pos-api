package com.beaconculinary.api.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthorizationSessionRepository extends JpaRepository<AuthorizationSession, Long> {
    Optional<AuthorizationSession> findBySessionToken(String sessionToken);
}
