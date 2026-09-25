package com.medicalchatbot.backend.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import com.medicalchatbot.backend.exception.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class PageRequestFactoryTest {

    private static final Set<String> FIELDS = Set.of("id", "createdAt");

    @Test
    void createsAllowListedSort() {
        var page = PageRequestFactory.create(2, 25, "createdAt,asc", "id", Sort.Direction.DESC, FIELDS);

        assertThat(page.getPageNumber()).isEqualTo(2);
        assertThat(page.getPageSize()).isEqualTo(25);
        assertThat(page.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void usesConfiguredDefaultForBlankSort() {
        var page = PageRequestFactory.create(0, 20, " ", "createdAt", Sort.Direction.DESC, FIELDS);

        assertThat(page.getSort().getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void rejectsUnknownFieldsAndUnboundedPages() {
        assertThrows(AppException.class,
                () -> PageRequestFactory.create(0, 20, "passwordHash,asc", "id", Sort.Direction.ASC, FIELDS));
        assertThrows(AppException.class,
                () -> PageRequestFactory.create(0, 101, "id,asc", "id", Sort.Direction.ASC, FIELDS));
    }
}
