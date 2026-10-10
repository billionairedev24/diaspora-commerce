package com.afrotransact.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class DefaultFefoAllocatorTest {

    private final FefoAllocator fefo = new DefaultFefoAllocator();

    @Test
    void picksEarliestExpiryFirstAcrossLots() {
        Lot late = Lots.sellable("L-LATE", Lots.TODAY.plusDays(400), 10);
        Lot early = Lots.sellable("L-EARLY", Lots.TODAY.plusDays(120), 3);
        Lot middle = Lots.sellable("L-MID", Lots.TODAY.plusDays(200), 2);

        FefoAllocator.Result r = fefo.allocate(List.of(late, early, middle), 7, Lots.TODAY, 7);

        assertThat(r.satisfied()).isTrue();
        assertThat(r.picks()).extracting(LotAllocation::lotNo).containsExactly("L-EARLY", "L-MID", "L-LATE");
        assertThat(r.picks()).extracting(LotAllocation::qty).containsExactly(3, 2, 2);
        assertThat(r.picks().stream().mapToLong(LotAllocation::costMinor).sum()).isEqualTo(7 * 192);
    }

    @Test
    void skipsLotsExpiringWithinMinimumRemainingDays() {
        Lot soon = Lots.sellable("L-SOON", Lots.TODAY.plusDays(6), 50);
        Lot ok = Lots.sellable("L-OK", Lots.TODAY.plusDays(7), 4);

        FefoAllocator.Result r = fefo.allocate(List.of(soon, ok), 4, Lots.TODAY, 7);

        assertThat(r.picks()).extracting(LotAllocation::lotNo).containsExactly("L-OK");
        assertThat(r.allocatable()).isEqualTo(4);
    }

    @Test
    void skipsHeldAndReservedUnitsAndPutsNonExpiringLotsLast() {
        Lot quarantined = Lots.lot("L-Q", Lots.TODAY.plusDays(100), 30, 0, LotStatus.QUARANTINE);
        Lot partlyReserved = Lots.lot("L-R", Lots.TODAY.plusDays(150), 5, 4, LotStatus.SELLABLE);
        Lot noExpiry = Lots.sellable("L-NONE", null, 10);

        FefoAllocator.Result r = fefo.allocate(List.of(noExpiry, quarantined, partlyReserved), 3, Lots.TODAY, 7);

        assertThat(r.picks()).extracting(LotAllocation::lotNo).containsExactly("L-R", "L-NONE");
        assertThat(r.picks()).extracting(LotAllocation::qty).containsExactly(1, 2);
    }

    @Test
    void reportsShortfallWhenEligibleLotsAreNotEnough() {
        FefoAllocator.Result r = fefo.allocate(List.of(Lots.sellable("L-1", Lots.TODAY.plusDays(300), 2),
                Lots.sellable("L-EXP", Lots.TODAY, 100)), 5, Lots.TODAY, 7);

        assertThat(r.satisfied()).isFalse();
        assertThat(r.allocatable()).isEqualTo(2);
    }
}
