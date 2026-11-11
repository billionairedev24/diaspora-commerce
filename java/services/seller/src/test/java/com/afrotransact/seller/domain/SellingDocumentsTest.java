package com.afrotransact.seller.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SellingDocumentsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);

    private static SellingDocument doc(SellingDocumentKind kind, LocalDate issued, LocalDate expires, boolean names) {
        return new SellingDocument("d", kind, "asset-1", "file.pdf", issued, expires, names, null);
    }

    private static List<SellingDocumentRequirement> needs(SellingDocumentKind... kinds) {
        return Arrays.stream(kinds).map(k -> new SellingDocumentRequirement(k, false)).toList();
    }

    @Test
    void anInvoiceMustBeDatedWithin180Days() {
        List<SellingDocumentRequirement> need = needs(SellingDocumentKind.PURCHASE_INVOICE);
        assertThatCode(() -> SellingDocuments.require(need, List.of(doc(SellingDocumentKind.PURCHASE_INVOICE,
                TODAY.minusDays(180), null, false)), TODAY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> SellingDocuments.require(need, List.of(doc(SellingDocumentKind.PURCHASE_INVOICE,
                TODAY.minusDays(181), null, false)), TODAY)).isInstanceOf(SellerRuleViolation.class)
                .satisfies(e -> assertThat(((SellerRuleViolation) e).fieldErrors().get("documents.PURCHASE_INVOICE"))
                        .contains("180 days"));
        assertThatThrownBy(() -> SellingDocuments.require(need, List.of(doc(SellingDocumentKind.PURCHASE_INVOICE,
                TODAY.plusDays(1), null, false)), TODAY)).isInstanceOf(SellerRuleViolation.class);
        assertThatThrownBy(() -> SellingDocuments.require(need, List.of(doc(SellingDocumentKind.PURCHASE_INVOICE,
                null, null, false)), TODAY)).isInstanceOf(SellerRuleViolation.class);
    }

    @Test
    void insuranceNeedsAFutureExpiryAndMustNameAfroTransact() {
        List<SellingDocumentRequirement> need = needs(SellingDocumentKind.LIABILITY_INSURANCE);
        LocalDate future = TODAY.plusDays(90);
        assertThatCode(() -> SellingDocuments.require(need, List.of(doc(SellingDocumentKind.LIABILITY_INSURANCE, null,
                future, true)), TODAY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> SellingDocuments.require(need, List.of(doc(SellingDocumentKind.LIABILITY_INSURANCE,
                null, future, false)), TODAY)).hasMessageContaining("missing or need fixing");
        assertThatThrownBy(() -> SellingDocuments.require(need, List.of(doc(SellingDocumentKind.LIABILITY_INSURANCE,
                null, null, true)), TODAY)).isInstanceOf(SellerRuleViolation.class);
        assertThatThrownBy(() -> SellingDocuments.require(need, List.of(doc(SellingDocumentKind.LIABILITY_INSURANCE,
                null, TODAY, true)), TODAY)).isInstanceOf(SellerRuleViolation.class);
    }

    @Test
    void missingRequiredDocumentsAreNamedAndOptionalOnesMayBeSkipped() {
        List<SellingDocumentRequirement> need = List.of(new SellingDocumentRequirement(SellingDocumentKind.PHOTOS_ALL_SIDES,
                false), new SellingDocumentRequirement(SellingDocumentKind.SAFETY_CERTIFICATE, true));
        assertThatThrownBy(() -> SellingDocuments.require(need, List.of(), TODAY)).satisfies(e -> {
            Map<String, String> errors = ((SellerRuleViolation) e).fieldErrors();
            assertThat(errors).containsKey("documents.PHOTOS_ALL_SIDES").doesNotContainKey("documents.SAFETY_CERTIFICATE");
        });
        assertThatThrownBy(() -> SellingDocuments.require(need, List.of(doc(SellingDocumentKind.PHOTOS_ALL_SIDES, null,
                null, false), doc(SellingDocumentKind.BRAND_AUTHORIZATION, null, null, false)), TODAY))
                .satisfies(e -> assertThat(((SellerRuleViolation) e).fieldErrors())
                        .containsKey("documents.BRAND_AUTHORIZATION"));
    }

    @Test
    void earliestExpiryIsTheEndOfTheEarliestDocumentDay() {
        var expiry = SellingApproval.earliestExpiry(List.of(doc(SellingDocumentKind.PERMIT_LICENCE, null,
                LocalDate.of(2027, 3, 1), false), doc(SellingDocumentKind.LIABILITY_INSURANCE, null,
                LocalDate.of(2027, 2, 1), true), doc(SellingDocumentKind.PHOTOS_ALL_SIDES, null, null, false)));
        assertThat(expiry).isEqualTo(Instant.parse("2027-02-01T23:59:59Z"));
        assertThat(SellingApproval.earliestExpiry(List.of())).isNull();
    }
}
