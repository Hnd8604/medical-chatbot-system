package com.medicalchatbot.backend.dto.response;

import java.util.List;

public record ModelPricingListResponse(
        List<ModelPricingInfo> pricing
) {
}
