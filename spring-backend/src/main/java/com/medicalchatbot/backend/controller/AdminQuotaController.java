package com.medicalchatbot.backend.controller;

import com.medicalchatbot.backend.dto.response.QuotaStatusResponse;
import com.medicalchatbot.backend.entity.QuotaPolicy;
import com.medicalchatbot.backend.service.QuotaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/quotas")
public class AdminQuotaController {

    private final QuotaService quotaService;

    public AdminQuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }


    @GetMapping("/policies")
    public List<QuotaPolicy> getAllPolicies() {
        return quotaService.getAllQuotaPolicies();
    }


    @GetMapping("/users/{username}")
    public QuotaStatusResponse getUserQuotaStatus(@PathVariable String username) {
        return quotaService.getUserStatusByUsername(username);
    }
}