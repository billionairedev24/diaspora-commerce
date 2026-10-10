package com.afrotransact.taxreturns.domain.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class DefaultReturnPolicyTest {

    private static final Instant DELIVERED = Instant.parse("2026-10-01T15:00:00Z");
    private static final ReturnPolicySettings SETTINGS = new ReturnPolicySettings(30, Map.of("electronics", 15),
            Set.of("fresh-produce"), 1_000, 20_000, Duration.ofHours(48), 14, 2_000, 6);
    private static final ReturnAddress ADDRESS = new ReturnAddress("Seller", "1 Main St", null, "Houston", "TX",
            "77002", "US", null);

    private final DefaultReturnPolicy policy = new DefaultReturnPolicy(SETTINGS);

    private static DeliveredLine line(String id, String type, boolean perishable, long unit, int qty, int returned,
                                      Instant delivered) {
        return new DeliveredLine(id, "s1", "AT" + id, "Item " + id, qty, unit, 0, type, perishable, delivered,
                returned);
    }

    private LineEligibility eligible(DeliveredLine l, FulfilmentType ff, DeliveryMethod dm, Instant now) {
        return policy.eligibility(l, ff, dm, Optional.empty(), now);
    }

    @Test
    void thirtyDayWindowFromDelivery() {
        DeliveredLine l = line("a", "apparel", false, 5_000, 1, 0, DELIVERED);
        LineEligibility in = eligible(l, FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER,
                DELIVERED.plus(Duration.ofDays(30)));
        assertThat(in.eligible()).isTrue();
        assertThat(in.windowEndsAt()).isEqualTo(DELIVERED.plus(Duration.ofDays(30)));
        assertThat(in.methods()).containsExactly(ReturnMethod.DROP_OFF);
        LineEligibility late = eligible(l, FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER,
                DELIVERED.plus(Duration.ofDays(30)).plusSeconds(1));
        assertThat(late.eligible()).isFalse();
        assertThat(late.ineligibleCode()).isEqualTo(DefaultReturnPolicy.WINDOW_CLOSED);
    }

    @Test
    void productTypeWindowAndSellerPolicyWindow() {
        DeliveredLine phone = line("a", "electronics", false, 50_000, 1, 0, DELIVERED);
        assertThat(eligible(phone, FulfilmentType.RETAIL_1P, DeliveryMethod.CARRIER,
                DELIVERED.plus(Duration.ofDays(16))).eligible()).isFalse();
        SellerReturnPolicy sixty = new SellerReturnPolicy("org_a", 60, 0, true, false, ADDRESS, 0);
        assertThat(policy.eligibility(phone, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER,
                Optional.of(sixty), DELIVERED.plus(Duration.ofDays(59))).eligible()).isTrue();
        assertThat(policy.eligibility(phone, FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER,
                Optional.of(sixty), DELIVERED.plus(Duration.ofDays(59))).eligible())
                .as("seller policy only governs seller-fulfilled shipments").isFalse();
    }

    @Test
    void notDeliveredOrAlreadyReturned() {
        assertThat(eligible(line("a", null, false, 5_000, 1, 0, null), FulfilmentType.FBA_AFROTRANSACT,
                DeliveryMethod.CARRIER, DELIVERED).ineligibleCode()).isEqualTo(DefaultReturnPolicy.NOT_DELIVERED);
        assertThat(eligible(line("a", null, false, 5_000, 2, 2, DELIVERED), FulfilmentType.FBA_AFROTRANSACT,
                DeliveryMethod.CARRIER, DELIVERED).ineligibleCode()).isEqualTo(DefaultReturnPolicy.ALREADY_RETURNED);
        LineEligibility partly = eligible(line("a", null, false, 5_000, 3, 1, DELIVERED),
                FulfilmentType.FBA_AFROTRANSACT, DeliveryMethod.CARRIER, DELIVERED);
        assertThat(partly.maxQuantity()).isEqualTo(2);
    }

    @Test
    void perishablesAndNonReturnableTypesOnlyReportAProblem() {
        LineEligibility food = eligible(line("a", "pantry", true, 1_850, 1, 0, DELIVERED),
                FulfilmentType.RETAIL_1P, DeliveryMethod.AFROTRANSACT_DELIVERY, DELIVERED);
        assertThat(food.reportProblemOnly()).isTrue();
        assertThat(food.methods()).containsExactly(ReturnMethod.NO_RETURN);
        LineEligibility produce = eligible(line("b", "fresh-produce", false, 1_850, 1, 0, DELIVERED),
                FulfilmentType.RETAIL_1P, DeliveryMethod.CARRIER, DELIVERED);
        assertThat(produce.reportProblemOnly()).isTrue();
    }

    @Test
    void lowValueLinesCanBeKeptAndDriverPickupOnlyForAfroTransactDelivery() {
        LineEligibility cheap = eligible(line("a", null, false, 500, 2, 0, DELIVERED),
                FulfilmentType.SELLER_FULFILLED, DeliveryMethod.AFROTRANSACT_DELIVERY, DELIVERED);
        assertThat(cheap.methods()).containsExactlyInAnyOrder(ReturnMethod.DROP_OFF, ReturnMethod.DRIVER_PICKUP,
                ReturnMethod.NO_RETURN);
        LineEligibility dear = eligible(line("a", null, false, 501, 2, 0, DELIVERED),
                FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER, DELIVERED);
        assertThat(dear.methods()).containsExactly(ReturnMethod.DROP_OFF);
    }

    @Test
    void validateCollectsEveryFieldError() {
        Map<String, DeliveredLine> delivered = Map.of(
                "food", line("food", "pantry", true, 1_850, 1, 0, DELIVERED),
                "dress", line("dress", "apparel", false, 6_499, 2, 0, DELIVERED));
        Map<String, LineEligibility> elig = Map.of(
                "food", eligible(delivered.get("food"), FulfilmentType.RETAIL_1P, DeliveryMethod.CARRIER, DELIVERED),
                "dress", eligible(delivered.get("dress"), FulfilmentType.RETAIL_1P, DeliveryMethod.CARRIER,
                        DELIVERED));
        List<ReturnLineRequest> bad = List.of(
                new ReturnLineRequest("food", 1, ReturnReason.NO_LONGER_NEEDED, null, List.of()),
                new ReturnLineRequest("dress", 3, ReturnReason.DAMAGED, null, List.of()),
                new ReturnLineRequest("ghost", 1, ReturnReason.DAMAGED, null, List.of("m1")));
        assertThatThrownBy(() -> policy.validate(ReturnMethod.DRIVER_PICKUP, bad, elig, delivered))
                .isInstanceOfSatisfying(ReturnRuleViolation.class, ex -> {
                    assertThat(ex.code()).isEqualTo("returns.not-eligible");
                    assertThat(ex.fields()).containsExactlyInAnyOrder("lines[0].reason", "lines[1].quantity",
                            "lines[1].evidenceAssetIds", "lines[2].lineId", "method");
                });
    }

    @Test
    void validRequestsPass() {
        DeliveredLine food = line("food", "pantry", true, 1_850, 1, 0, DELIVERED);
        DeliveredLine dress = line("dress", "apparel", false, 6_499, 2, 0, DELIVERED);
        LineEligibility foodE = eligible(food, FulfilmentType.RETAIL_1P, DeliveryMethod.CARRIER, DELIVERED);
        LineEligibility dressE = eligible(dress, FulfilmentType.RETAIL_1P, DeliveryMethod.CARRIER, DELIVERED);
        policy.validate(ReturnMethod.NO_RETURN, List.of(new ReturnLineRequest("food", 1,
                ReturnReason.EXPIRED_OR_SPOILED, "Mouldy", List.of("asset_1"))), Map.of("food", foodE),
                Map.of("food", food));
        policy.validate(ReturnMethod.DROP_OFF, List.of(new ReturnLineRequest("dress", 2,
                ReturnReason.NO_LONGER_NEEDED, null, null)), Map.of("dress", dressE), Map.of("dress", dress));
    }

    @Test
    void sellerThatRefusesNoFaultReturnsOnlyTakesFaultReasons() {
        SellerReturnPolicy strict = new SellerReturnPolicy("org_a", 30, 0, false, false, ADDRESS, 0);
        DeliveredLine l = line("a", "apparel", false, 6_499, 1, 0, DELIVERED);
        LineEligibility e = policy.eligibility(l, FulfilmentType.SELLER_FULFILLED, DeliveryMethod.CARRIER,
                Optional.of(strict), DELIVERED);
        assertThat(e.sellerFaultOnly()).isTrue();
        assertThatThrownBy(() -> policy.validate(ReturnMethod.DROP_OFF, List.of(new ReturnLineRequest("a", 1,
                ReturnReason.ORDERED_BY_MISTAKE, null, null)), Map.of("a", e), Map.of("a", l)))
                .isInstanceOf(ReturnRuleViolation.class);
        policy.validate(ReturnMethod.DROP_OFF, List.of(new ReturnLineRequest("a", 1, ReturnReason.NOT_AS_DESCRIBED,
                null, null)), Map.of("a", e), Map.of("a", l));
    }

    @Test
    void tooManyPhotosAndEmptyRequests() {
        DeliveredLine l = line("a", "apparel", false, 6_499, 1, 0, DELIVERED);
        LineEligibility e = eligible(l, FulfilmentType.RETAIL_1P, DeliveryMethod.CARRIER, DELIVERED);
        assertThatThrownBy(() -> policy.validate(ReturnMethod.DROP_OFF, List.of(new ReturnLineRequest("a", 1,
                ReturnReason.DAMAGED, null, List.of("1", "2", "3", "4", "5", "6", "7"))), Map.of("a", e),
                Map.of("a", l))).isInstanceOfSatisfying(ReturnRuleViolation.class,
                        ex -> assertThat(ex.fields()).containsExactly("lines[0].evidenceAssetIds"));
        assertThatThrownBy(() -> policy.validate(ReturnMethod.DROP_OFF, List.of(), Map.of(), Map.of()))
                .isInstanceOfSatisfying(ReturnRuleViolation.class,
                        ex -> assertThat(ex.fields()).containsExactly("lines"));
    }

    @Test
    void approvalAndFirstScanRules() {
        assertThat(policy.autoApprove(FulfilmentType.FBA_AFROTRANSACT, ReturnMethod.DROP_OFF, 50_000)).isTrue();
        assertThat(policy.autoApprove(FulfilmentType.SELLER_FULFILLED, ReturnMethod.DROP_OFF, 500)).isFalse();
        assertThat(policy.autoApprove(FulfilmentType.SELLER_FULFILLED, ReturnMethod.NO_RETURN, 1_000)).isTrue();
        assertThat(policy.autoApprove(FulfilmentType.RETAIL_1P, ReturnMethod.NO_RETURN, 1_001)).isFalse();
        assertThat(policy.approvalDueAt(DELIVERED)).isEqualTo(DELIVERED.plus(Duration.ofHours(48)));

        assertThat(policy.refundAtFirstScan(FulfilmentType.RETAIL_1P, ReturnMethod.DROP_OFF, 20_000,
                Optional.empty())).isTrue();
        assertThat(policy.refundAtFirstScan(FulfilmentType.RETAIL_1P, ReturnMethod.DROP_OFF, 20_001,
                Optional.empty())).isFalse();
        assertThat(policy.refundAtFirstScan(FulfilmentType.RETAIL_1P, ReturnMethod.NO_RETURN, 100,
                Optional.empty())).isFalse();
        SellerReturnPolicy fast = new SellerReturnPolicy("org_a", 30, 0, true, true, ADDRESS, 0);
        assertThat(policy.refundAtFirstScan(FulfilmentType.SELLER_FULFILLED, ReturnMethod.DROP_OFF, 5_000,
                Optional.empty())).isFalse();
        assertThat(policy.refundAtFirstScan(FulfilmentType.SELLER_FULFILLED, ReturnMethod.DROP_OFF, 5_000,
                Optional.of(fast))).isTrue();
    }

    @Test
    void restockingFeeOnlyForSellerFulfilledAndCapped() {
        SellerReturnPolicy greedy = new SellerReturnPolicy("org_a", 30, 5_000, true, false, ADDRESS, 0);
        assertThat(policy.restockingFeeBps(FulfilmentType.SELLER_FULFILLED, Optional.of(greedy))).isEqualTo(2_000);
        assertThat(policy.restockingFeeBps(FulfilmentType.FBA_AFROTRANSACT, Optional.of(greedy))).isZero();
        assertThat(policy.restockingFeeBps(FulfilmentType.SELLER_FULFILLED, Optional.empty())).isZero();
    }
}
