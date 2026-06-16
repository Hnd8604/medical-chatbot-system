package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.response.AdminUserItemResponse;
import com.medicalchatbot.backend.dto.response.AdminUserListResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AdminUserService(
            UserRepository userRepository,
            CurrentUserService currentUserService,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper
    ) {
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AdminUserListResponse listUsers(int page, int size) {
        Page<User> users = userRepository.findAllByOrderByCreatedAtDesc(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return new AdminUserListResponse(
                users.getNumber(),
                users.getSize(),
                users.getTotalElements(),
                users.getTotalPages(),
                users.map(AdminUserItemResponse::from).getContent()
        );
    }

    @Transactional
    public AdminUserItemResponse updateStatus(UUID userId, UserStatus status) {
        User actor = currentUserService.requireCurrentUser();
        User target = requireUser(userId);
        if (actor.getId().equals(target.getId()) && status != UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Admin khong the tu khoa hoac vo hieu hoa chinh minh.");
        }

        UserStatus oldStatus = target.getStatus();
        target.updateStatus(status);
        userRepository.save(target);
        logChange(actor, target, "ADMIN_UPDATE_USER_STATUS", "status", oldStatus.name(), status.name());
        return AdminUserItemResponse.from(target);
    }

    @Transactional
    public AdminUserItemResponse updateRole(UUID userId, UserRole role) {
        User actor = currentUserService.requireCurrentUser();
        User target = requireUser(userId);
        if (actor.getId().equals(target.getId()) && role != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Admin khong the tu ha quyen cua chinh minh.");
        }

        UserRole oldRole = target.getRole();
        target.updateRole(role);
        userRepository.save(target);
        logChange(actor, target, "ADMIN_UPDATE_USER_ROLE", "role", oldRole.name(), role.name());
        return AdminUserItemResponse.from(target);
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Khong tim thay nguoi dung."));
    }

    private void logChange(User actor, User target, String action, String field, String oldValue, String newValue) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("field", field);
        metadata.put("old_value", oldValue);
        metadata.put("new_value", newValue);
        auditLogRepository.save(actor, null, action, "app_user", target.getId().toString(), metadata);
    }
}
