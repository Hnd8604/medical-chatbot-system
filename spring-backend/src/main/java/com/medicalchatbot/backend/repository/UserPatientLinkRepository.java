package com.medicalchatbot.backend.repository;

import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.entity.UserPatientLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserPatientLinkRepository extends JpaRepository<UserPatientLink, UUID> {

    @Query("""
            select l.fhirPatientId
            from UserPatientLink l
            where l.user.id = :userId
            order by l.primaryLink desc, l.createdAt asc
            """)
    List<String> findPatientIdsForUser(@Param("userId") UUID userId);
}
