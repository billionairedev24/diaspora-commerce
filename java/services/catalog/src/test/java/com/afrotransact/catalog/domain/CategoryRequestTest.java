package com.afrotransact.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CategoryRequestTest {

    private final Instant now = Instant.parse("2026-10-10T00:00:00Z");

    @Test
    void approvesOnceThenRefusesFurtherDecisions() {
        CategoryRequest open = CategoryRequest.open(UUID.randomUUID(), "REQ-000001", "org_1",
                "Fashion › Women", "Bridal aso-ebi sets", "6-piece aso-ebi set", "Pieces, colour, fabric", now);
        UUID leaf = UUID.randomUUID();
        CategoryRequest approved = open.approve(leaf, "ok", now);
        assertThat(approved.status()).isEqualTo(CategoryRequestStatus.APPROVED);
        assertThat(approved.resolvedCategoryId()).isEqualTo(leaf);
        assertThat(approved.fullPath()).isEqualTo("Fashion › Women › Bridal aso-ebi sets");
        assertThatThrownBy(() -> approved.reject("late", now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresMeaningfulText() {
        assertThatThrownBy(() -> CategoryRequest.open(UUID.randomUUID(), "REQ-000002", "org_1", "x", "y", "z",
                "w", now)).isInstanceOf(CatalogRuleViolation.class);
    }
}
