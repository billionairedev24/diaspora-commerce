package com.afrotransact.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StockPositionTest {

    private final StockPosition position = new StockPosition("AT-JOL-6", "HOU-FC", "org", 10, 8, 0, 0, 3);

    @Test
    void availableIsOnHandMinusReserved() {
        assertThat(position.available()).isEqualTo(2);
    }

    @Test
    void adjustmentCanNeverTakeOnHandBelowReserved() {
        assertThatThrownBy(() -> position.adjustSellable(-3))
                .isInstanceOfSatisfying(InventoryRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.below-reserved"));
        assertThat(position.adjustSellable(-2).onHand()).isEqualTo(8);
        assertThat(position.adjustSellable(5).available()).isEqualTo(7);
    }

    @Test
    void quarantineIsGuardedLikeAnAdjustment() {
        assertThatThrownBy(() -> position.hold(3)).isInstanceOf(InventoryRuleViolation.class);
        StockPosition held = position.hold(2);
        assertThat(held.onHand()).isEqualTo(8);
        assertThat(held.damaged()).isEqualTo(2);
    }

    @Test
    void reserveBeyondAvailableReportsShortfall() {
        assertThatThrownBy(() -> position.reserve(3))
                .isInstanceOfSatisfying(InsufficientStockException.class, ex -> {
                    assertThat(ex.shortfalls()).singleElement().satisfies(s -> {
                        assertThat(s.requested()).isEqualTo(3);
                        assertThat(s.available()).isEqualTo(2);
                        assertThat(s.missing()).isEqualTo(1);
                    });
                });
    }

    @Test
    void shipReducesOnHandAndReservedTogether() {
        StockPosition shipped = position.ship(8);
        assertThat(shipped.onHand()).isEqualTo(2);
        assertThat(shipped.reserved()).isZero();
        assertThat(shipped.available()).isEqualTo(2);
        assertThatThrownBy(() -> position.ship(9)).isInstanceOf(InventoryStateConflict.class);
        assertThatThrownBy(() -> position.release(9)).isInstanceOf(InventoryStateConflict.class);
    }
}
