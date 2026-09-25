package com.medicalchatbot.backend.repository;

import com.medicalchatbot.backend.entity.BackupHistory;
import com.medicalchatbot.backend.enums.BackupStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BackupHistoryRepository extends JpaRepository<BackupHistory, UUID> {

    Page<BackupHistory> findAllByOrderByCreatedAtDesc(Pageable pageable);

    boolean existsByStatus(BackupStatus status);
}
