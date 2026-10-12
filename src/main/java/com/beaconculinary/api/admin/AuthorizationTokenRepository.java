package com.beaconculinary.api.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AuthorizationTokenRepository extends JpaRepository<AuthorizationToken, Long> {
    Optional<AuthorizationToken> findByToken(String token);

    /** Deletes the token only if it is still unused; returns rows affected (0 or 1). A concurrent
     * transaction deleting the same token blocks on the row lock and then gets 0. */
    @Modifying
    @Query("DELETE FROM AuthorizationToken t WHERE t.id = :id AND t.used = false")
    int deleteIfUnused(@Param("id") Long id);
}
