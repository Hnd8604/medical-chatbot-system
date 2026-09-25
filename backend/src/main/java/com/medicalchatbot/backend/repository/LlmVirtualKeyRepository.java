package com.medicalchatbot.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.entity.LlmVirtualKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LlmVirtualKeyRepository extends JpaRepository<LlmVirtualKey, UUID> {

    Optional<LlmVirtualKey> findByUserId(UUID userId);

    @Query("""
            select k from LlmVirtualKey k
            where k.userId in (
                select u.id from User u where u.quotaPolicy.id = :policyId
            )
            """)
    List<LlmVirtualKey> findAllByQuotaPolicyId(@Param("policyId") UUID policyId);
}
