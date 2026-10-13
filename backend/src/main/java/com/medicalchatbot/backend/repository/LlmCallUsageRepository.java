package com.medicalchatbot.backend.repository;

import java.util.UUID;

import com.medicalchatbot.backend.entity.LlmCallUsage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmCallUsageRepository extends JpaRepository<LlmCallUsage, UUID> {
}
