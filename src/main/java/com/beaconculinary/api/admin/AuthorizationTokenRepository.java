package com.beaconculinary.api.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthorizationTokenRepository extends JpaRepository<AuthorizationToken, Long> {
    Optional<AuthorizationToken> findByToken(String token);
}
