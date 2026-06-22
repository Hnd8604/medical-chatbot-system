package com.medicalchatbot.backend.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.dto.request.QuotaPolicyUpsertRequest;
import com.medicalchatbot.backend.dto.response.QuotaPolicyAdminResponse;
import com.medicalchatbot.backend.service.QuotaPolicyAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/quota-policies")
@RequiredArgsConstructor
public class QuotaPolicyAdminController {

    private final QuotaPolicyAdminService quotaPolicyAdminService;

    @GetMapping
    public ResponseEntity<List<QuotaPolicyAdminResponse>> list() {
        return ResponseEntity.ok(quotaPolicyAdminService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<QuotaPolicyAdminResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(quotaPolicyAdminService.get(id));
    }

    @PostMapping
    public ResponseEntity<QuotaPolicyAdminResponse> create(
            @Valid @RequestBody QuotaPolicyUpsertRequest request
    ) {
        QuotaPolicyAdminResponse created = quotaPolicyAdminService.create(request);
        return ResponseEntity.created(URI.create("/api/admin/quota-policies/" + created.id()))
                .body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<QuotaPolicyAdminResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody QuotaPolicyUpsertRequest request
    ) {
        return ResponseEntity.ok(quotaPolicyAdminService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        quotaPolicyAdminService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
