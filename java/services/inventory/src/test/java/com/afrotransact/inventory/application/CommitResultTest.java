package com.afrotransact.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.inventory.domain.LotAllocation;
import com.afrotransact.inventory.domain.Reservation;
import com.afrotransact.inventory.domain.ReservationLine;
import com.afrotransact.inventory.domain.ReservationStatus;
import org.junit.jupiter.api.Test;

class CommitResultTest {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");

    @Test
    void cogsIsValuedInUsdWithTheAmountsInTheCurrenciesPaidAlongside() {
        // 3 units from a Mexican lot (MXN 120.00 = $6.92 each) and 2 from a US lot ($1.92 each).
        LotAllocation peso = new LotAllocation(UUID.randomUUID(), "L-MX", 3, null, 12_000, "MXN", 692);
        LotAllocation dollar = new LotAllocation(UUID.randomUUID(), "L-US", 2, null, 192, "USD", 192);
        ReservationLine line = new ReservationLine(1, "AT-JOL-6", "HOU-FC", 5, List.of(peso, dollar), "g1",
                ReservationLine.LineStatus.COMMITTED, "org_retail");
        Reservation r = new Reservation(UUID.randomUUID(), "ORD-1", ReservationStatus.COMMITTED, "key", null, NOW, NOW,
                List.of(line), 0);

        CommitResult result = CommitResult.of(r, false);

        assertThat(result.cogsUsdMinor()).isEqualTo(3 * 692 + 2 * 192);
        assertThat(result.cogs()).containsExactly(new CommitResult.Money("MXN", 36_000),
                new CommitResult.Money("USD", 384));
        assertThat(result.lines().getFirst().cogsUsdMinor()).isEqualTo(2_460);
    }
}
