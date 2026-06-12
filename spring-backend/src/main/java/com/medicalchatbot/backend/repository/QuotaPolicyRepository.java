package com.medicalchatbot.backend.repository;

import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.dto.response.QuotaPolicyInfo;
import com.medicalchatbot.backend.entity.QuotaPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuotaPolicyRepository extends JpaRepository<QuotaPolicy, UUID> {

    @Query("""
            select new com.medicalchatbot.backend.dto.response.QuotaPolicyInfo(
                q.name,
                q.dailyRequestLimit,
                q.dailyTokenLimit,
                q.dailyCostLimitUsd
            )
            from User u
            join u.quotaPolicy q
            where u.id = :userId
            """)
    Optional<QuotaPolicyInfo> findByUserId(@Param("userId") UUID userId);


    @Query("""
    SELECT q.rateLimitPerMinute
    FROM User u
    JOIN u.quotaPolicy q
    WHERE u.username = :username
""")
    Integer findRateLimitByUsername(String username);
}
