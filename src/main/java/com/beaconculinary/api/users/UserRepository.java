package com.beaconculinary.api.users;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> { //JpaRepository is an interface that exposes paging and sorting
    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    List<User> findByActiveTrueAndRoleIn(List<Role> roles);
}
