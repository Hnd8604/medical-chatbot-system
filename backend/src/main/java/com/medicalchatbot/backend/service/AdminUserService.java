package com.medicalchatbot.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medicalchatbot.backend.dto.response.AdminUserItemResponse;
import com.medicalchatbot.backend.dto.response.AdminUserListResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.entity.UserPatientLink;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import com.medicalchatbot.backend.repository.AuditLogRepository;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final UserPatientLinkRepository userPatientLinkRepository;
    private final CurrentUserService currentUserService;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public AdminUserListResponse listUsers(int page, int size) {
        return listUsers(page, size, null);
    }

    @Transactional(readOnly = true)
    public AdminUserListResponse listUsers(int page, int size, String search) {
        String query = search == null ? "" : search.trim();
        Page<User> users = query.isEmpty()
                ? userRepository.findAllByOrderByCreatedAtDesc(
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                : userRepository.searchByUsernameOrEmail(
                        query, PageRequest.of(page, size));
        List<User> content = users.getContent();
        Map<UUID, List<UserPatientLink>> linksByUserId = linksByUserId(content);
        return new AdminUserListResponse(
                users.getNumber(),
                users.getSize(),
                users.getTotalElements(),
                users.getTotalPages(),
                content.stream()
                        .map(user -> AdminUserItemResponse.from(
                                user,
                                linksByUserId.getOrDefault(user.getId(), List.of())
                        ))
                        .toList()
        );
    }

    @Transactional
    public AdminUserItemResponse updateStatus(UUID userId, UserStatus status) {
        User actor = currentUserService.requireCurrentUser();
        User target = requireUser(userId);
        if (actor.getId().equals(target.getId()) && status != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Admin khong the tu khoa hoac vo hieu hoa chinh minh.");
        }

        UserStatus oldStatus = target.getStatus();
        target.updateStatus(status);
        userRepository.save(target);
        logChange(actor, target, "ADMIN_UPDATE_USER_STATUS", "status", oldStatus.name(), status.name());
        return responseFor(target);
    }

    @Transactional
    public AdminUserItemResponse updateRole(UUID userId, UserRole role) {
        User actor = currentUserService.requireCurrentUser();
        User target = requireUser(userId);
        if (actor.getId().equals(target.getId()) && role != UserRole.ADMIN) {
            throw new AppException(ErrorCode.INVALID_ARGUMENT, "Admin khong the tu ha quyen cua chinh minh.");
        }

        UserRole oldRole = target.getRole();
        target.updateRole(role);
        userRepository.save(target);
        logChange(actor, target, "ADMIN_UPDATE_USER_ROLE", "role", oldRole.name(), role.name());
        return responseFor(target);
    }

    private Map<UUID, List<UserPatientLink>> linksByUserId(List<User> users) {
        List<UUID> userIds = users.stream()
                .map(User::getId)
                .toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userPatientLinkRepository.findLinksForUsers(userIds).stream()
                .collect(Collectors.groupingBy(link -> link.getUser().getId()));
    }

    private AdminUserItemResponse responseFor(User user) {
        return AdminUserItemResponse.from(user, userPatientLinkRepository.findLinksForUser(user.getId()));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.RESOURCE_NOT_FOUND, "Khong tim thay nguoi dung."));
    }

    private void logChange(User actor, User target, String action, String field, String oldValue, String newValue) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("field", field);
        metadata.put("old_value", oldValue);
        metadata.put("new_value", newValue);
        auditLogRepository.save(actor, null, action, "app_user", target.getId().toString(), metadata);
    }
}
