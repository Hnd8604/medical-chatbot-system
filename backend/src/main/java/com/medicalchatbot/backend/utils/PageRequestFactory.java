package com.medicalchatbot.backend.utils;

import java.util.Set;

import com.medicalchatbot.backend.exception.AppException;
import com.medicalchatbot.backend.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Builds bounded, allow-listed page requests from public query parameters. */
public final class PageRequestFactory {

    public static final int MAX_PAGE_SIZE = 100;

    private PageRequestFactory() {
    }

    public static PageRequest create(
            int page,
            int size,
            String sort,
            String defaultField,
            Sort.Direction defaultDirection,
            Set<String> allowedFields
    ) {
        if (page < 0) {
            throw invalid("Trang phải lớn hơn hoặc bằng 0.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("Kích thước trang phải từ 1 đến " + MAX_PAGE_SIZE + ".");
        }
        if (allowedFields == null || !allowedFields.contains(defaultField)) {
            throw new IllegalArgumentException("Default sort field must be allow-listed");
        }

        Sort.Order order = parseSort(sort, defaultField, defaultDirection, allowedFields);
        return PageRequest.of(page, size, Sort.by(order));
    }

    static Sort.Order parseSort(
            String sort,
            String defaultField,
            Sort.Direction defaultDirection,
            Set<String> allowedFields
    ) {
        if (sort == null || sort.isBlank()) {
            return new Sort.Order(defaultDirection, defaultField);
        }

        String[] parts = sort.split(",", -1);
        if (parts.length > 2) {
            throw invalid("Tham số sort phải có dạng field,asc hoặc field,desc.");
        }

        String field = parts[0].trim();
        if (!allowedFields.contains(field)) {
            throw invalid("Trường sắp xếp không được hỗ trợ: " + field);
        }

        Sort.Direction direction = defaultDirection;
        if (parts.length == 2 && !parts[1].isBlank()) {
            direction = Sort.Direction.fromOptionalString(parts[1].trim())
                    .orElseThrow(() -> invalid("Hướng sắp xếp chỉ có thể là asc hoặc desc."));
        }
        return new Sort.Order(direction, field);
    }

    private static AppException invalid(String message) {
        return new AppException(ErrorCode.INVALID_ARGUMENT, message);
    }
}
