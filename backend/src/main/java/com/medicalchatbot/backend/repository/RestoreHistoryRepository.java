package com.medicalchatbot.backend.repository;

import com.medicalchatbot.backend.entity.RestoreHistory;
import com.medicalchatbot.backend.enums.BackupStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RestoreHistoryRepository extends JpaRepository<RestoreHistory, UUID> {

    Page<RestoreHistory> findAllByOrderByCreatedAtDesc(Pageable pageable);

    boolean existsByStatus(BackupStatus status);
}
