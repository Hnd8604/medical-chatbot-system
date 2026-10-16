package com.medicalchatbot.backend.dto.request.chat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FeedbackRequest(
        @NotNull(message = "Vui lòng chọn số sao đánh giá.")
        @Min(value = 1, message = "Số sao đánh giá phải từ 1 đến 5.")
        @Max(value = 5, message = "Số sao đánh giá phải từ 1 đến 5.")
        Integer rating,

        @Size(max = 2000, message = "Nhận xét không được vượt quá {max} ký tự.")
        String comment
) {
}
