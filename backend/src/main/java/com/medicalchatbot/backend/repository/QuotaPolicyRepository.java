package com.medicalchatbot.backend.repository;

import java.util.Optional;
import java.util.UUID;

import com.medicalchatbot.backend.entity.QuotaPolicy;
import com.medicalchatbot.backend.repository.projection.QuotaLimitsProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuotaPolicyRepository extends JpaRepository<QuotaPolicy, UUID> {

    Optional<QuotaPolicy> findByName(String name);

    boolean existsByName(String name);

    @Query("""
            select new com.medicalchatbot.backend.repository.projection.QuotaLimitsProjection(
                q.name,
                q.dailyRequestLimit,
                q.dailyTokenLimit,
                q.dailyCostLimitUsd
            )
            from User u
            join u.quotaPolicy q
            where u.id = :userId
            """)
    Optional<QuotaLimitsProjection> findLimitsByUserId(@Param("userId") UUID userId);

    @Query("""
            select q.rateLimitPerMinute
            from User u
            join u.quotaPolicy q
            where u.id = :userId
            """)
    Optional<Integer> findRateLimitPerMinuteByUserId(@Param("userId") UUID userId);
}
