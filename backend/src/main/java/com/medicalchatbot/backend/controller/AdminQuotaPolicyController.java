package com.medicalchatbot.backend.controller;

import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.dto.request.QuotaPolicyRequest;
import com.medicalchatbot.backend.dto.response.QuotaPolicyResponse;
import com.medicalchatbot.backend.service.QuotaPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@RequestMapping("/api/admin/quota-policies")
public class AdminQuotaPolicyController {

    private final QuotaPolicyService quotaPolicyService;

    @GetMapping
    public List<QuotaPolicyResponse> list() {
        return quotaPolicyService.list();
    }

    @GetMapping("/{id}")
    public QuotaPolicyResponse get(@PathVariable UUID id) {
        return quotaPolicyService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuotaPolicyResponse create(@Valid @RequestBody QuotaPolicyRequest request) {
        return quotaPolicyService.create(request);
    }

    @PutMapping("/{id}")
    public QuotaPolicyResponse update(@PathVariable UUID id, @Valid @RequestBody QuotaPolicyRequest request) {
        return quotaPolicyService.update(id, request);
    }

    /** Refused while users are still assigned to the policy. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        quotaPolicyService.delete(id);
    }
}
