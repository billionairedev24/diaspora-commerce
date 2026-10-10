package com.afrotransact.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DefaultRefundAllocatorTest {

    private final RefundAllocator allocator = new DefaultRefundAllocator();

    private static PaymentLine captured(String id, long items, long ship, long tax) {
        return PaymentLine.uncaptured(Fixtures.line(id, "org_a", "Groceries", Fulfilment.SELF, 1, items, ship, tax))
                .captured(UUID.randomUUID(), new LineSplit(id, "org_a", 800, Fixtures.usd(0), Fixtures.usd(0),
                        false));
    }

    @Test
    void spreadsAnAmountOverLinesAndComponentsExactly() {
        List<RefundAllocator.Share> shares = allocator.allocate(List.of(captured("L1", 10_000, 500, 866),
                captured("L2", 3_000, 0, 248)), Fixtures.usd(7_307));
        assertThat(shares).hasSize(2);
        assertThat(shares.stream().mapToLong(s -> s.total().minor()).sum()).isEqualTo(7_307);
        RefundAllocator.Share first = shares.getFirst();
        assertThat(first.items().minor()).isLessThanOrEqualTo(10_000);
        assertThat(first.shipping().minor()).isLessThanOrEqualTo(500);
        assertThat(first.tax().minor()).isLessThanOrEqualTo(866);
    }

    @Test
    void halfOfOneLineSplitsItsComponentsInProportion() {
        RefundAllocator.Share s = allocator.allocate(List.of(captured("L1", 10_000, 500, 866)), Fixtures.usd(5_683))
                .getFirst();
        assertThat(s.items().minor()).isEqualTo(5_000);
        assertThat(s.shipping().minor()).isEqualTo(250);
        assertThat(s.tax().minor()).isEqualTo(433);
    }

    @Test
    void noAmountRefundsWhatIsLeftAndNeverMore() {
        PaymentLine partly = captured("L1", 1_000, 0, 80).refunded(new RefundLine("L1", "org_a", "US-TX", false,
                Fixtures.usd(400), Fixtures.usd(0), Fixtures.usd(32), Fixtures.usd(0)));
        RefundAllocator.Share rest = allocator.allocate(List.of(partly), null).getFirst();
        assertThat(rest.items().minor()).isEqualTo(600);
        assertThat(rest.tax().minor()).isEqualTo(48);
        assertThatThrownBy(() -> allocator.allocate(List.of(partly), Fixtures.usd(649)))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.refund-exceeds-remaining"));
        assertThatThrownBy(() -> allocator.allocate(List.of(PaymentLine.uncaptured(Fixtures.line("L9", "org_a",
                "Groceries", Fulfilment.SELF, 1, 100, 0, 0))), null))
                .isInstanceOfSatisfying(PaymentsRuleViolation.class,
                        e -> assertThat(e.code()).isEqualTo("payments.line-not-captured"));
    }

    @Test
    void shippingTaxStaysWithinTheLineTax() {
        assertThatThrownBy(() -> new OrderLine("L1", "org_a", "Groceries", Fulfilment.SELF, 1, Fixtures.usd(100),
                Fixtures.usd(10), Fixtures.usd(8), "US-TX", Fixtures.usd(9))).isInstanceOf(PaymentsRuleViolation.class);
        OrderLine ok = new OrderLine("L1", "org_a", "Groceries", Fulfilment.SELF, 1, Fixtures.usd(100),
                Fixtures.usd(10), Fixtures.usd(9), "US-TX", Fixtures.usd(1));
        assertThat(ok.itemsTax().minor()).isEqualTo(8);
    }
}
