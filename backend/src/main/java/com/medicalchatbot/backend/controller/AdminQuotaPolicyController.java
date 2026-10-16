package com.medicalchatbot.backend.controller;

import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.constant.ResponseMessage;
import com.medicalchatbot.backend.dto.request.admin.QuotaPolicyRequest;
import com.medicalchatbot.backend.dto.response.ApiResponse;
import com.medicalchatbot.backend.dto.response.admin.QuotaPolicyResponse;
import com.medicalchatbot.backend.service.QuotaPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/quota-policies")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
@Tag(name = "Admin - Quota policies", description = "Daily AI limits and rate limits assigned to users")
public class AdminQuotaPolicyController {

    QuotaPolicyService quotaPolicyService;

    @GetMapping
    @Operation(summary = "List quota policies")
    public ApiResponse<List<QuotaPolicyResponse>> list() {
        return ApiResponse.<List<QuotaPolicyResponse>>builder()
                .result(quotaPolicyService.list())
                .message(ResponseMessage.GET_QUOTA_POLICIES_SUCCESS)
                .build();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a quota policy")
    public ApiResponse<QuotaPolicyResponse> get(@PathVariable UUID id) {
        return ApiResponse.<QuotaPolicyResponse>builder()
                .result(quotaPolicyService.get(id))
                .message(ResponseMessage.GET_QUOTA_POLICY_SUCCESS)
                .build();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a quota policy", description = "Policy names are unique.")
    public ApiResponse<QuotaPolicyResponse> create(@Valid @RequestBody QuotaPolicyRequest request) {
        return ApiResponse.<QuotaPolicyResponse>builder()
                .result(quotaPolicyService.create(request))
                .message(ResponseMessage.CREATE_QUOTA_POLICY_SUCCESS)
                .build();
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a quota policy", description = "An omitted rateLimitPerMinute falls back to the default.")
    public ApiResponse<QuotaPolicyResponse> update(@PathVariable UUID id, @Valid @RequestBody QuotaPolicyRequest request) {
        return ApiResponse.<QuotaPolicyResponse>builder()
                .result(quotaPolicyService.update(id, request))
                .message(ResponseMessage.UPDATE_QUOTA_POLICY_SUCCESS)
                .build();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a quota policy", description = "Refused while users are still assigned to it.")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        quotaPolicyService.delete(id);
        return ApiResponse.<Void>builder().message(ResponseMessage.DELETE_QUOTA_POLICY_SUCCESS).build();
    }
}
