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

    @Query("""
            select l
            from UserPatientLink l
            where l.user.id = :userId
              and l.relationship = 'SELF'
            order by l.primaryLink desc, l.createdAt asc
            """)
    List<UserPatientLink> findSelfLinksForUser(@Param("userId") UUID userId);

    @Query("""
            select count(l) > 0
            from UserPatientLink l
            where lower(l.fhirPatientId) = lower(:patientId)
              and l.relationship = 'SELF'
              and l.user.id <> :userId
            """)
    boolean existsSelfLinkForOtherUser(
            @Param("patientId") String patientId,
            @Param("userId") UUID userId
    );

    @Query("""
            select l
            from UserPatientLink l
            where l.user.id = :userId
            order by l.primaryLink desc, l.createdAt asc
            """)
    List<UserPatientLink> findLinksForUser(@Param("userId") UUID userId);

    @Query("""
            select l
            from UserPatientLink l
            where l.user.id in :userIds
            order by l.user.id asc, l.primaryLink desc, l.createdAt asc
            """)
    List<UserPatientLink> findLinksForUsers(@Param("userIds") List<UUID> userIds);
}
