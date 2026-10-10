package com.afrotransact.offers.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultCouponPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final CouponUsage FRESH = new CouponUsage(0, 0, false);
    private final DefaultCouponPolicy policy = new DefaultCouponPolicy();

    private static final CouponLine JOLLOF = new CouponLine("o1", "AT00000001", "org_mamatee", "cat-pantry",
            Fulfilment.AFROTRANSACT, 899, 2);
    private static final CouponLine DRESS = new CouponLine("o2", "AT00000002", "org_lagos", "cat-fashion",
            Fulfilment.SELLER, 6499, 1);

    @Test
    void percentRoundsDownAndRespectsTheCap() {
        CouponEvaluation e = policy.evaluate(coupon(CouponType.PERCENT, 15, null), cart(JOLLOF, DRESS), FRESH, NOW);

        assertThat(e.applicable()).isTrue();
        assertThat(e.eligibleSubtotalMinor()).isEqualTo(8297);
        assertThat(e.discountMinor()).isEqualTo(1244);
        assertThat(e.lines()).extracting(LineDiscount::discountMinor).containsExactly(269L, 975L);

        CouponEvaluation capped = policy.evaluate(coupon(CouponType.PERCENT, 15, 500L), cart(JOLLOF, DRESS), FRESH,
                NOW);
        assertThat(capped.discountMinor()).isEqualTo(500);
        assertThat(capped.lines().stream().mapToLong(LineDiscount::discountMinor).sum()).isEqualTo(500L);
    }

    @Test
    void fixedNeverExceedsTheEligibleSubtotal() {
        CouponEvaluation e = policy.evaluate(coupon(CouponType.FIXED, 10_000, null), cart(JOLLOF), FRESH, NOW);

        assertThat(e.discountMinor()).isEqualTo(1798);
    }

    @Test
    void freeShippingDiscountsShippingOnly() {
        Coupon c = coupon(CouponType.FREE_SHIPPING, 0, null);
        CouponEvaluation e = policy.evaluate(c, new CouponCart("USD", List.of(DRESS), 599, false), FRESH, NOW);

        assertThat(e.discountMinor()).isZero();
        assertThat(e.shippingDiscountMinor()).isEqualTo(599);
        assertThat(policy.evaluate(c, new CouponCart("USD", List.of(DRESS), 0, false), FRESH, NOW).rejection())
                .isEqualTo(CouponRejection.NO_SHIPPING_TO_DISCOUNT);
    }

    @Test
    void minimumSpendCountsOnlyEligibleLines() {
        Coupon c = with(coupon(CouponType.FIXED, 500, null), CouponScope.FULFILLED_BY_AFROTRANSACT, null, 2500);

        CouponEvaluation e = policy.evaluate(c, cart(JOLLOF, DRESS), FRESH, NOW);

        assertThat(e.rejection()).isEqualTo(CouponRejection.BELOW_MINIMUM_SPEND);
        assertThat(e.message()).contains("7.02 USD");
    }

    @Test
    void usageLimitsPerCouponAndPerCustomerAreEnforced() {
        Coupon c = limits(coupon(CouponType.PERCENT, 10, null), 100, 1);

        assertThat(policy.evaluate(c, cart(JOLLOF), new CouponUsage(100, 0, false), NOW).rejection())
                .isEqualTo(CouponRejection.TOTAL_LIMIT_REACHED);
        assertThat(policy.evaluate(c, cart(JOLLOF), new CouponUsage(5, 1, false), NOW).rejection())
                .isEqualTo(CouponRejection.CUSTOMER_LIMIT_REACHED);
        assertThat(policy.evaluate(c, cart(JOLLOF), new CouponUsage(99, 0, false), NOW).applicable()).isTrue();
    }

    @Test
    void validityWindowStatusCurrencyAndClipAreChecked() {
        Coupon c = coupon(CouponType.PERCENT, 10, null);
        assertThat(policy.evaluate(c, cart(JOLLOF), FRESH, NOW.minusSeconds(86_400 * 30)).rejection())
                .isEqualTo(CouponRejection.NOT_STARTED);
        assertThat(policy.evaluate(c, cart(JOLLOF), FRESH, c.endsAt()).rejection())
                .isEqualTo(CouponRejection.EXPIRED);
        assertThat(policy.evaluate(c.withStatus(CouponStatus.PAUSED, NOW), cart(JOLLOF), FRESH, NOW).rejection())
                .isEqualTo(CouponRejection.NOT_ACTIVE);
        assertThat(policy.evaluate(c, new CouponCart("GBP", List.of(JOLLOF), 0, false), FRESH, NOW).rejection())
                .isEqualTo(CouponRejection.CURRENCY_MISMATCH);

        Coupon clip = new Coupon(c.id(), c.code(), null, c.type(), c.value(), "USD", null, CouponScope.SITEWIDE, null,
                0, 0, 0, true, CouponFunding.PLATFORM, null, c.startsAt(), c.endsAt(), CouponStatus.ACTIVE, 0, "t",
                NOW, NOW, 0);
        assertThat(policy.evaluate(clip, cart(JOLLOF), FRESH, NOW).rejection()).isEqualTo(CouponRejection.NOT_CLIPPED);
        assertThat(policy.evaluate(clip, cart(JOLLOF), new CouponUsage(0, 0, true), NOW).applicable()).isTrue();
    }

    @Test
    void sellerFundedCouponsOnlyDiscountTheFundingSellersLines() {
        Coupon c = new Coupon(UUID.randomUUID(), "MAMATEE20", null, CouponType.PERCENT, 20, "USD", null,
                CouponScope.SITEWIDE, null, 0, 0, 0, false, CouponFunding.SELLER, "org_mamatee",
                NOW.minusSeconds(60), null, CouponStatus.ACTIVE, 0, "t", NOW, NOW, 0);

        CouponEvaluation e = policy.evaluate(c, cart(JOLLOF, DRESS), FRESH, NOW);

        assertThat(e.eligibleSubtotalMinor()).isEqualTo(1798);
        assertThat(e.discountMinor()).isEqualTo(359);
        assertThat(e.lines()).singleElement().satisfies(l -> {
            assertThat(l.sellerOrgId()).isEqualTo("org_mamatee");
            assertThat(l.discountMinor()).isEqualTo(359);
        });
        assertThat(policy.evaluate(c, cart(DRESS), FRESH, NOW).rejection())
                .isEqualTo(CouponRejection.NO_ELIGIBLE_ITEMS);
    }

    @Test
    void scopesMatchCategorySellerProductAndFirstOrder() {
        Coupon base = coupon(CouponType.FIXED, 100, null);
        assertThat(policy.appliesTo(with(base, CouponScope.CATEGORY, "cat-pantry", 0), JOLLOF)).isTrue();
        assertThat(policy.appliesTo(with(base, CouponScope.CATEGORY, "cat-pantry", 0), DRESS)).isFalse();
        assertThat(policy.appliesTo(with(base, CouponScope.SELLER, "org_lagos", 0), DRESS)).isTrue();
        assertThat(policy.appliesTo(with(base, CouponScope.PRODUCT, "AT00000001", 0), JOLLOF)).isTrue();
        assertThat(policy.appliesTo(with(base, CouponScope.FULFILLED_BY_AFROTRANSACT, null, 0), DRESS)).isFalse();

        Coupon first = with(base, CouponScope.FIRST_ORDER, null, 0);
        assertThat(policy.evaluate(first, cart(JOLLOF), FRESH, NOW).rejection())
                .isEqualTo(CouponRejection.FIRST_ORDER_ONLY);
        assertThat(policy.evaluate(first, new CouponCart("USD", List.of(JOLLOF), 0, true), FRESH, NOW).applicable())
                .isTrue();
    }

    @Test
    void allocationAlwaysSumsToTheDiscount() {
        List<CouponLine> lines = List.of(
                new CouponLine("a", "AT0000000A", "org_a", null, Fulfilment.SELLER, 333, 1),
                new CouponLine("b", "AT0000000B", "org_a", null, Fulfilment.SELLER, 333, 1),
                new CouponLine("c", "AT0000000C", "org_a", null, Fulfilment.SELLER, 334, 1));

        List<LineDiscount> out = DefaultCouponPolicy.allocate(100, 1000, lines);

        assertThat(out).extracting(LineDiscount::discountMinor).containsExactly(33L, 33L, 34L);
        assertThat(DefaultCouponPolicy.allocate(1, 1000, lines)).extracting(LineDiscount::discountMinor)
                .containsExactly(0L, 0L, 1L);
    }

    @Test
    void validationListsEveryBadField() {
        Coupon bad = new Coupon(UUID.randomUUID(), "x!", null, CouponType.PERCENT, 150, "usd", 100L,
                CouponScope.CATEGORY, null, -1, -1, 0, false, CouponFunding.SELLER, null, NOW, NOW.minusSeconds(1),
                CouponStatus.ACTIVE, 0, "t", NOW, NOW, 0);

        assertThatThrownBy(() -> policy.validate(bad))
                .isInstanceOfSatisfying(OfferRuleViolation.class, ex -> assertThat(ex.fieldErrors()).containsKeys(
                        "code", "value", "currency", "scopeRef", "minSpendMinor", "totalLimit", "fundedByOrg",
                        "endsAt"));
        policy.validate(coupon(CouponType.PERCENT, 10, 500L));
    }

    private static CouponCart cart(CouponLine... lines) {
        return new CouponCart("USD", List.of(lines), 0, false);
    }

    private static Coupon coupon(CouponType type, long value, Long cap) {
        return new Coupon(UUID.randomUUID(), "WELCOME10", null, type, value, "USD", cap, CouponScope.SITEWIDE, null,
                0, 0, 0, false, CouponFunding.PLATFORM, null, NOW.minusSeconds(86_400), NOW.plusSeconds(86_400 * 7),
                CouponStatus.ACTIVE, 0, "t", NOW, NOW, 0);
    }

    private static Coupon with(Coupon c, CouponScope scope, String ref, long minSpend) {
        return new Coupon(c.id(), c.code(), null, c.type(), c.value(), c.currency(), c.maxDiscountMinor(), scope, ref,
                minSpend, c.totalLimit(), c.perCustomerLimit(), c.clipRequired(), c.funding(), c.fundedByOrg(),
                c.startsAt(), c.endsAt(), c.status(), 0, "t", NOW, NOW, 0);
    }

    private static Coupon limits(Coupon c, int total, int perCustomer) {
        return new Coupon(c.id(), c.code(), null, c.type(), c.value(), c.currency(), c.maxDiscountMinor(), c.scope(),
                c.scopeRef(), c.minSpendMinor(), total, perCustomer, c.clipRequired(), c.funding(), c.fundedByOrg(),
                c.startsAt(), c.endsAt(), c.status(), 0, "t", NOW, NOW, 0);
    }
}
