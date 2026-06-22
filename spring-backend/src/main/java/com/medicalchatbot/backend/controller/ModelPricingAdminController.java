package com.medicalchatbot.backend.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.medicalchatbot.backend.dto.request.ModelPricingUpsertRequest;
import com.medicalchatbot.backend.dto.response.ModelPricingAdminResponse;
import com.medicalchatbot.backend.service.ModelPricingAdminService;
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
@RequestMapping("/api/admin/model-pricing")
@RequiredArgsConstructor
public class ModelPricingAdminController {

    private final ModelPricingAdminService modelPricingAdminService;

    @GetMapping
    public ResponseEntity<List<ModelPricingAdminResponse>> list() {
        return ResponseEntity.ok(modelPricingAdminService.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ModelPricingAdminResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(modelPricingAdminService.get(id));
    }

    @PostMapping
    public ResponseEntity<ModelPricingAdminResponse> create(
            @Valid @RequestBody ModelPricingUpsertRequest request
    ) {
        ModelPricingAdminResponse created = modelPricingAdminService.create(request);
        return ResponseEntity.created(URI.create("/api/admin/model-pricing/" + created.id()))
                .body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ModelPricingAdminResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody ModelPricingUpsertRequest request
    ) {
        return ResponseEntity.ok(modelPricingAdminService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        modelPricingAdminService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
