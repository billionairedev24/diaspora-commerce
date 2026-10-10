package com.afrotransact.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LotTest {

    @Test
    void reservedLotCannotBeQuarantinedOrExpired() {
        Lot lot = Lots.lot("L-1", Lots.TODAY.plusDays(100), 10, 2, LotStatus.SELLABLE);
        assertThatThrownBy(() -> lot.quarantine("Broken seals"))
                .isInstanceOfSatisfying(InventoryStateConflict.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.lot-reserved"));
        assertThatThrownBy(lot::expire).isInstanceOf(InventoryStateConflict.class);
    }

    @Test
    void cycleCountCannotGoBelowReserved() {
        Lot lot = Lots.lot("L-1", Lots.TODAY.plusDays(100), 10, 6, LotStatus.SELLABLE);
        assertThatThrownBy(() -> lot.countedAs(5, "count")).isInstanceOf(InventoryRuleViolation.class);
        assertThat(lot.countedAs(6, "count").qtyOnHand()).isEqualTo(6);
    }

    @Test
    void writeOffIsPartialUntilNothingIsLeft() {
        Lot lot = Lots.lot("L-1", Lots.TODAY.plusDays(100), 10, 0, LotStatus.QUARANTINE);
        Lot partial = lot.writeOff(4, "Crushed cartons");
        assertThat(partial.status()).isEqualTo(LotStatus.QUARANTINE);
        assertThat(partial.qtyOnHand()).isEqualTo(6);
        Lot gone = partial.writeOff(6, "Disposed");
        assertThat(gone.status()).isEqualTo(LotStatus.WRITTEN_OFF);
        assertThatThrownBy(() -> gone.writeOff(1, "again")).isInstanceOf(InventoryStateConflict.class);
    }

    @Test
    void expiredQuarantineCannotBeReleasedForSale() {
        Lot lot = Lots.lot("L-1", Lots.TODAY, 10, 0, LotStatus.QUARANTINE);
        assertThatThrownBy(() -> lot.releaseQuarantine("ok", Lots.TODAY))
                .isInstanceOfSatisfying(InventoryRuleViolation.class,
                        ex -> assertThat(ex.code()).isEqualTo("inventory.lot-expired"));
        Lot fresh = Lots.lot("L-2", Lots.TODAY.plusDays(30), 10, 0, LotStatus.QUARANTINE);
        assertThat(fresh.releaseQuarantine("Seals fine", Lots.TODAY).status()).isEqualTo(LotStatus.SELLABLE);
    }

    @Test
    void markdownFlagsAtThirtyAndSixtyDays() {
        assertThat(MarkdownFlag.of(Lots.TODAY.plusDays(30), Lots.TODAY)).isEqualTo(MarkdownFlag.WITHIN_30);
        assertThat(MarkdownFlag.of(Lots.TODAY.plusDays(31), Lots.TODAY)).isEqualTo(MarkdownFlag.WITHIN_60);
        assertThat(MarkdownFlag.of(Lots.TODAY.plusDays(61), Lots.TODAY)).isEqualTo(MarkdownFlag.NONE);
        assertThat(MarkdownFlag.of(null, Lots.TODAY)).isEqualTo(MarkdownFlag.NONE);
    }
}
