package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DeliveryPricingTest {

    private static Money usd(long minor) {
        return Fixtures.usd(minor);
    }

    private static BigDecimal mi(String miles) {
        return new BigDecimal(miles);
    }

    @Test
    void flatChargesPerPackage() {
        DeliveryTariff flat = DeliveryTariff.flat(usd(350));
        assertThat(flat.price(mi("0"), 1).minor()).isEqualTo(350);
        assertThat(flat.price(mi("18.2"), 3).minor()).isEqualTo(1050);
    }

    @Test
    void distanceIsBasePlusPerMileClampedToMinAndMax() {
        DeliveryTariff t = Fixtures.launchDelivery(List.of()).customerCharge();
        assertThat(t.price(mi("0.5"), 1).minor()).isEqualTo(599);            // 499 + 43 → raised to the minimum
        assertThat(t.price(mi("7.4"), 4).minor()).isEqualTo(499 + 629);      // per trip: packages don't count
        assertThat(t.price(mi("24.99"), 1).minor()).isEqualTo(2499);         // 499 + 2124 → capped
    }

    @Test
    void bandsReplaceBasePlusPerMileInsideTheirRange() {
        DeliveryTariff banded = new DeliveryTariff(DeliveryMode.DISTANCE, usd(0), usd(500), 100, List.of(
                new DeliveryBand(mi("0"), mi("3"), usd(399)),
                new DeliveryBand(mi("3"), mi("10"), usd(699))), usd(0), null);
        assertThat(banded.price(mi("0"), 1).minor()).isEqualTo(399);
        assertThat(banded.price(mi("2.99"), 1).minor()).isEqualTo(399);
        assertThat(banded.price(mi("3"), 1).minor()).isEqualTo(699);
        assertThat(banded.price(mi("12.345"), 1).minor()).isEqualTo(500 + 1235); // past the last band, half up
        assertThatThrownBy(() -> new DeliveryTariff(DeliveryMode.DISTANCE, usd(0), usd(0), 0, List.of(
                new DeliveryBand(mi("0"), mi("5"), usd(1)), new DeliveryBand(mi("4"), mi("8"), usd(2))), usd(0), null))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.invalid-delivery-pricing"));
        assertThatThrownBy(() -> new DeliveryTariff(DeliveryMode.DISTANCE, usd(0), usd(0), 0, List.of(), usd(900),
                usd(100))).isInstanceOf(PaymentsRuleViolation.class);
    }

    @Test
    void beyondTheRadiusTheOptionIsUnavailable() {
        FeeSchedule s = Fixtures.scheduleWithDelivery(Fixtures.launchDelivery(List.of()));
        DeliveryQuote in = s.quoteDelivery("org_a", mi("25"), 1, Fixtures.RETAIL);
        DeliveryQuote out = s.quoteDelivery("org_a", mi("25.01"), 1, Fixtures.RETAIL);
        assertThat(in.available()).isTrue();
        assertThat(out.available()).isFalse();
        assertThat(out.unavailableReason()).isEqualTo(DeliveryQuote.BEYOND_MAX_RADIUS);
        assertThat(out.customerCharge().minor()).isZero();
        assertThat(out.scheduleVersion()).isEqualTo(1);
        DeliveryQuote none = Fixtures.schedule().quoteDelivery("org_a", mi("2"), 1, Fixtures.RETAIL);
        assertThat(none.available()).isFalse();
        assertThat(none.unavailableReason()).isEqualTo(DeliveryQuote.NOT_CONFIGURED);
    }

    @Test
    void sellerOverridesWinAndEitherSideCanBeZero() {
        DeliveryPricing p = new DeliveryPricing(DeliveryTariff.free(Fixtures.USD), DeliveryTariff.flat(usd(250)),
                mi("15"), List.of(new SellerDeliveryOverride("org_vip", DeliveryTariff.free(Fixtures.USD))));
        FeeSchedule s = Fixtures.scheduleWithDelivery(p);
        DeliveryQuote vip = s.quoteDelivery("org_vip", mi("4"), 2, Fixtures.RETAIL);
        DeliveryQuote other = s.quoteDelivery("org_a", mi("4"), 2, Fixtures.RETAIL);
        assertThat(vip.sellerFee().minor()).isZero();
        assertThat(vip.sellerFeeSource()).isEqualTo("OVERRIDE");
        assertThat(vip.customerCharge().minor()).isZero();
        assertThat(other.sellerFee().minor()).isEqualTo(500);
        assertThat(other.sellerFeeSource()).isEqualTo("DEFAULT");
        assertThatThrownBy(() -> new DeliveryPricing(DeliveryTariff.free(Fixtures.USD), DeliveryTariff.free("USD"),
                mi("0"), List.of())).isInstanceOf(PaymentsRuleViolation.class);
    }

    @Test
    void cancellingAVersionKeepsItsDeliveryPricing() {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        FeeSchedule future = new FeeSchedule(UUID.randomUUID(), 2, now.plusSeconds(60), "USD", Map.of(),
                1000, List.of(), 290, usd(30), usd(325), true, null, "t", now, null,
                Fixtures.launchDelivery(List.of()));
        assertThat(future.cancel(now, "fin", "typo").deliveryPricing()).isEqualTo(future.deliveryPricing());
    }

    /** Seller at fault: the delivery charge may be refunded and AfroTransact keeps the seller fee. */
    @Test
    void sellerFaultRefundKeepsTheSellerFee() {
        PaymentLine pl = captured(Fixtures.deliveryLine("D1", "org_c", "Groceries", 2, 3000, 1128, 248, 200));
        RefundLine r = RefundLine.allocate(pl, usd(3000), usd(1128), usd(248), true, RefundFault.SELLER);
        assertThat(r.commissionReversed().minor()).isEqualTo(240);
        assertThat(r.deliveryFeeReversed().minor()).isZero();
        assertThat(r.sellerDebit().minor()).isEqualTo(3000 - 240);
        Journal j = Postings.refund(UUID.randomUUID(), Instant.EPOCH, "r1", "o1", "p1", "re_1", List.of(r),
                Fixtures.RETAIL);
        assertThat(amount(j, "4200", EntrySide.DEBIT)).isEqualTo(1128);
        assertThat(amount(j, "4250", EntrySide.DEBIT)).isZero();
        assertThat(amount(j, "2200", EntrySide.CREDIT)).isEqualTo(4376);
    }

    /** Customer at fault: no delivery-charge refund, the seller gets its fee back pro rata (cumulatively). */
    @Test
    void customerFaultRefundReturnsTheSellerFeeProRata() {
        PaymentLine pl = captured(Fixtures.deliveryLine("D1", "org_c", "Groceries", 2, 3000, 1128, 248, 200));
        RefundLine half = RefundLine.allocate(pl, usd(1500), usd(0), usd(124), true, RefundFault.CUSTOMER);
        assertThat(half.deliveryFeeReversed().minor()).isEqualTo(100);
        assertThat(half.commissionReversed().minor()).isEqualTo(120);
        assertThat(half.sellerDebit().minor()).isEqualTo(1500 - 120 - 100);
        Journal j = Postings.refund(UUID.randomUUID(), Instant.EPOCH, "r1", "o1", "p1", "re_1", List.of(half),
                Fixtures.RETAIL);
        assertThat(amount(j, "4250", EntrySide.DEBIT)).isEqualTo(100);
        assertThat(amount(j, "2000", EntrySide.DEBIT)).isEqualTo(1280);
        assertThat(amount(j, "2200", EntrySide.CREDIT)).isEqualTo(1624);
        PaymentLine after = pl.refunded(half);
        RefundLine rest = RefundLine.allocate(after, usd(1500), usd(0), usd(124), true, RefundFault.CUSTOMER);
        assertThat(rest.deliveryFeeReversed().minor()).isEqualTo(100);
        assertThat(after.refunded(rest).deliveryFeeReversed().minor()).isEqualTo(200);
        assertThatThrownBy(() -> RefundLine.allocate(pl, usd(0), usd(1), usd(0), true, RefundFault.CUSTOMER))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.delivery-charge-not-refundable"));
    }

    @Test
    void customerFaultAllocationLeavesTheDeliveryChargeOut() {
        PaymentLine pl = captured(Fixtures.deliveryLine("D1", "org_c", "Groceries", 2, 3000, 1128, 248, 200));
        List<RefundAllocator.Share> shares = new DefaultRefundAllocator().allocate(List.of(pl), null,
                RefundFault.CUSTOMER);
        assertThat(shares).singleElement().satisfies(s -> {
            assertThat(s.shipping().minor()).isZero();
            assertThat(s.total().minor()).isEqualTo(3000 + 248);
        });
        assertThat(new DefaultRefundAllocator().allocate(List.of(pl), null).getFirst().shipping().minor())
                .isEqualTo(1128);
    }

    private static PaymentLine captured(OrderLine line) {
        FeeSchedule s = Fixtures.scheduleWithDelivery(Fixtures.launchDelivery(List.of()));
        PaymentSplit split = new DefaultPaymentSplitter(Fixtures.RETAIL).split(List.of(line), s, usd(100));
        return PaymentLine.uncaptured(line).captured(UUID.randomUUID(), split.line(line.lineId()).orElseThrow());
    }

    private static long amount(Journal j, String code, EntrySide side) {
        return j.lines().stream().filter(l -> l.account().chart().code().equals(code) && l.side() == side)
                .mapToLong(l -> l.amount().minor()).sum();
    }
}
