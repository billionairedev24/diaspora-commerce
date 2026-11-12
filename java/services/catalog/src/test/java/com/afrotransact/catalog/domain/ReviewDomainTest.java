package com.afrotransact.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** Business days, the staff-editable policy, change request rules and duplicate hints. */
class ReviewDomainTest {

    private static final Instant FRIDAY = Instant.parse("2026-11-13T10:00:00Z");

    @Test
    void twoBusinessDaysAfterAFridayIsTuesday() {
        assertThat(BusinessDays.add(FRIDAY, 2)).isEqualTo(Instant.parse("2026-11-17T10:00:00Z"));
        assertThat(BusinessDays.add(Instant.parse("2026-11-11T10:00:00Z"), 2))
                .isEqualTo(Instant.parse("2026-11-13T10:00:00Z"));
        assertThat(BusinessDays.add(FRIDAY, 0)).isEqualTo(FRIDAY);
    }

    @Test
    void thePolicyChecksEveryThresholdAndTellsWhichOneIsWrong() {
        CatalogPolicy good = policy(List.of("02", "99"), "model", 2, 3);
        assertThat(good.problems()).isEmpty();

        assertThat(policy(List.of("2"), "model", 2, 3).problems()).containsKey("reservedPrefixes");
        assertThat(policy(List.of("02"), "Model Number", 2, 3).problems()).containsKey("mpnAttributeKey");
        assertThat(policy(List.of("02"), "model", 0, 3).problems()).containsKey("reviewSlaBusinessDays");
        assertThat(policy(List.of("02"), "model", 4, 3).problems()).containsKey("alertAfterBusinessDays");
    }

    @Test
    void aChangeRequestMustFitItsType() {
        Map<String, String> noValue = ChangeRequestPolicy.problems(ChangeRequestType.TITLE, Map.of(), null, 1000, "AT00000001");
        assertThat(noValue).containsKey("payload.value");

        assertThat(ChangeRequestPolicy.problems(ChangeRequestType.TITLE, Map.of("value", "Plantain chips 8 oz"), null,
                1000, "AT00000001")).isEmpty();
        assertThat(ChangeRequestPolicy.problems(ChangeRequestType.ATTRIBUTE, Map.of("key", "material", "value", "Cotton"),
                null, 1000, "AT00000001")).isEmpty();
        assertThat(ChangeRequestPolicy.problems(ChangeRequestType.DUPLICATE_PAGE,
                Map.of("duplicateOfAtin", "AT00000001"), null, 1000, "AT00000001")).containsKey("payload.duplicateOfAtin");
        assertThat(ChangeRequestPolicy.problems(ChangeRequestType.WRONG_MATCH, Map.of("gtin", "012345678905"), null, 1000,
                "AT00000001")).containsKey("payload.variantAtin");
        assertThat(ChangeRequestPolicy.problems(ChangeRequestType.TITLE, Map.of("value", "Plantain chips"), "x".repeat(20),
                10, "AT00000001")).containsKey("note");
    }

    @Test
    void anImageRequestNeedsAnHttpsLinkAnAllowedTypeAndASize() {
        Map<String, Object> bad = Map.of("url", "http://x/a.jpg", "contentType", "image/gif", "width", 0, "height", 0,
                "bytes", 0);
        Map<String, String> errors = ChangeRequestPolicy.problems(ChangeRequestType.IMAGE_ADD, bad, null, 1000, "AT00000001");
        assertThat(errors).containsKeys("payload.url", "payload.contentType", "payload.bytes", "payload.width");

        Map<String, Object> ok = Map.of("url", "https://cdn.example/a.jpg", "contentType", "image/jpeg", "width", 1200,
                "height", 1200, "bytes", 200_000);
        assertThat(ChangeRequestPolicy.problems(ChangeRequestType.IMAGE_ADD, ok, null, 1000, "AT00000001")).isEmpty();
    }

    @Test
    void aDecisionNeedsAReasonAndHappensOnce() {
        ProductChangeRequest waiting = ProductChangeRequest.filed(UUID.randomUUID(), "AT00000001", null, null,
                ChangeRequestType.TITLE, "", Map.of("value", "New title"), null, "org_b", "user_b", FRIDAY, FRIDAY);

        assertThatThrownBy(() -> waiting.rejected("staff", "no", FRIDAY)).isInstanceOf(IllegalArgumentException.class);
        ProductChangeRequest applied = waiting.applied("staff", "Matches the pack", null, FRIDAY);
        assertThat(applied.status()).isEqualTo(ChangeRequestStatus.APPLIED);
        assertThat(applied.decidedBy()).isEqualTo("staff");
        assertThat(applied.reason()).isEqualTo("Matches the pack");
        assertThatThrownBy(() -> applied.rejected("staff", "Too late", FRIDAY)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> applied.withdrawn("user_b", FRIDAY)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExemptionDecisionNeedsAReasonAndHappensOnce() {
        GtinExemptionRequest waiting = GtinExemptionRequest.filed(UUID.randomUUID(), "AT00000001", "org_b",
                "Handmade", ExemptionRequirement.KNOWN_GS1_BRAND, FRIDAY, FRIDAY);

        assertThatThrownBy(() -> waiting.approved("staff", " ", FRIDAY)).isInstanceOf(IllegalArgumentException.class);
        GtinExemptionRequest approved = waiting.approved("staff", "Checked the maker's catalogue", FRIDAY);
        assertThat(approved.status()).isEqualTo(ExemptionStatus.APPROVED);
        assertThatThrownBy(() -> approved.rejected("staff", "Changed my mind", FRIDAY))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hintsShowTheSamePartNumberAndSimilarTitlesButNeverThePageItself() {
        Product page = page("Ankara print tote bag", Map.of("brand_id", "b1", "model", "TB-100"));
        Product samePart = page("Tote bag in Ankara", Map.of("brand_id", "b1", "model", "tb 100"));
        Product similarTitle = page("Ankara print tote bag large", Map.of("brand_id", "b1", "model", "TB-200"));
        Product unrelated = page("Shea butter 8 oz", Map.of("brand_id", "b1", "model", "SB-1"));

        List<DuplicateHints.Hint> hints = DuplicateHints.of(page, List.of(page, samePart, similarTitle, unrelated), "model");

        assertThat(hints).extracting(DuplicateHints.Hint::atin).containsExactly(samePart.atin().value(),
                similarTitle.atin().value());
        assertThat(hints.getFirst().matchedOn()).contains("BRAND_MPN");
        assertThat(hints.getFirst().score()).isEqualTo(1.0);
        assertThat(hints.get(1).matchedOn()).containsExactly("TITLE");
    }

    private static int counter = 1000;

    private static Product page(String title, Map<String, String> attributes) {
        String atin = String.format("AT%08d", ++counter);
        return new Product(UUID.randomUUID(), new Atin(atin), new ListingOwner(ListingOwnerKind.SELLER, "org_a"), null,
                null, "custom", title, attributes, List.of(), "", List.of(), ProductStatus.LIVE, null, FRIDAY, FRIDAY, 0);
    }

    private static CatalogPolicy policy(List<String> prefixes, String key, int sla, int alert) {
        return new CatalogPolicy(prefixes, key, sla, alert, 1000, 1, List.of(), true, null, null, 0);
    }
}
