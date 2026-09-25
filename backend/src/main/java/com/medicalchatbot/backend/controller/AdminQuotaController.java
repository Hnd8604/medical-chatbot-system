package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.dto.response.QuotaPolicyAdminResponse;
import com.medicalchatbot.backend.service.QuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/quotas")
public class AdminQuotaController {

    private final QuotaService quotaService;

    @GetMapping("/policies")
    public List<QuotaPolicyAdminResponse> getAllPolicies() {
        return quotaService.getAllQuotaPolicies().stream()
                .map(QuotaPolicyAdminResponse::from)
                .toList();
    }


    @GetMapping("/users/{username}")
    public QuotaStatusResponse getUserQuotaStatus(@PathVariable String username) {
        return quotaService.getUserStatusByUsername(username);
    }
}
