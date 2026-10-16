package com.medicalchatbot.backend.mapper;

import com.medicalchatbot.backend.dto.request.admin.QuotaPolicyRequest;
import com.medicalchatbot.backend.dto.response.admin.QuotaPolicyResponse;
import com.medicalchatbot.backend.entity.QuotaPolicy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

@Mapper
public interface QuotaPolicyMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    QuotaPolicy toQuotaPolicy(QuotaPolicyRequest request);

    QuotaPolicyResponse toQuotaPolicyResponse(QuotaPolicy policy);

    /** Full replace: an omitted {@code rateLimitPerMinute} goes back to the system default. */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateQuotaPolicy(@MappingTarget QuotaPolicy policy, QuotaPolicyRequest request);
}
