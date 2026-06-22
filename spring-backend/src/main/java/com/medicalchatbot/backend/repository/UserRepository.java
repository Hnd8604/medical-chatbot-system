package com.medicalchatbot.backend.repository;

import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    @Query("""
            select u
            from User u
            where lower(u.username) = lower(:credential)
               or lower(coalesce(u.email, '')) = lower(:credential)
            """)
    Optional<User> findByUsernameOrEmailIgnoreCase(@Param("credential") String credential);

    Page<User> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByQuotaPolicyId(UUID quotaPolicyId);

    default Optional<UUID> findIdByUsername(String username) {
        return findByUsername(username).map(User::getId);
    }
}
