package com.medicalchatbot.backend.repository;

import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    long countByQuotaPolicyId(UUID quotaPolicyId);

    default Optional<UUID> findIdByUsername(String username) {
        return findByUsername(username).map(User::getId);
    }
}
