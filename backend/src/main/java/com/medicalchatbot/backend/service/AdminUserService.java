package com.medicalchatbot.backend.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.medicalchatbot.backend.dto.response.AdminUserResponse;
import com.medicalchatbot.backend.dto.response.PageResponse;
import com.medicalchatbot.backend.entity.User;
import com.medicalchatbot.backend.entity.UserPatientLink;
import com.medicalchatbot.backend.enums.AuditAction;
import com.medicalchatbot.backend.enums.AuditResource;
import com.medicalchatbot.backend.enums.UserRole;
import com.medicalchatbot.backend.enums.UserStatus;
import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.mapper.UserMapper;
import com.medicalchatbot.backend.repository.UserPatientLinkRepository;
import com.medicalchatbot.backend.repository.UserRepository;
import com.medicalchatbot.backend.util.TextUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User administration: listing accounts and changing their role or status. */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;
    private final UserPatientLinkRepository userPatientLinkRepository;
    private final CurrentUserService currentUserService;
    private final AuditService auditService;
    private final UserMapper userMapper;

    /** Newest accounts first; a {@code search} term matches username, email or display name. */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> list(int page, int size, String search) {
        String query = TextUtils.trimToNull(search);
        Page<User> users = query == null
                ? userRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                : userRepository.search(query, PageRequest.of(page, size));
        Map<UUID, List<UserPatientLink>> linksByUser = userPatientLinkRepository
                .findByUserIds(users.getContent().stream().map(User::getId).toList()).stream()
                .collect(Collectors.groupingBy(link -> link.getUser().getId()));
        return PageResponse.of(users,
                user -> userMapper.toAdminUserResponse(user, linksByUser.getOrDefault(user.getId(), List.of())));
    }

    @Transactional
    public AdminUserResponse updateStatus(UUID userId, UserStatus status) {
        User actor = currentUserService.requireCurrentUser();
        User target = requireUser(userId);
        if (actor.getId().equals(target.getId()) && status != UserStatus.ACTIVE) {
            throw AppException.invalid("Admin không thể tự khóa hoặc vô hiệu hóa chính mình.");
        }
        UserStatus previous = target.getStatus();
        target.setStatus(status);
        auditChange(actor, target, AuditAction.ADMIN_UPDATE_USER_STATUS, "status", previous.name(), status.name());
        return toResponse(target);
    }

    @Transactional
    public AdminUserResponse updateRole(UUID userId, UserRole role) {
        User actor = currentUserService.requireCurrentUser();
        User target = requireUser(userId);
        if (actor.getId().equals(target.getId()) && role != UserRole.ADMIN) {
            throw AppException.invalid("Admin không thể tự hạ quyền của chính mình.");
        }
        UserRole previous = target.getRole();
        target.setRole(role);
        auditChange(actor, target, AuditAction.ADMIN_UPDATE_USER_ROLE, "role", previous.name(), role.name());
        return toResponse(target);
    }

    private AdminUserResponse toResponse(User user) {
        return userMapper.toAdminUserResponse(user, userPatientLinkRepository.findByUserIds(List.of(user.getId())));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> AppException.notFound("Không tìm thấy người dùng."));
    }

    private void auditChange(User actor, User target, AuditAction action, String field, String oldValue, String newValue) {
        auditService.record(actor, action, AuditResource.APP_USER, target.getId().toString(), auditService.metadata()
                .put("field", field)
                .put("old_value", oldValue)
                .put("new_value", newValue));
    }
}
