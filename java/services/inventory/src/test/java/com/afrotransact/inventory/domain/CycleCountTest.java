package com.afrotransact.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CycleCountTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private static CycleCount count() {
        return new CycleCount(UUID.randomUUID(), "CC-000001", "HOU-FC", "SKU-1", null, null, false,
                CycleCountStatus.OPEN, null, List.of(
                new CycleCountLine(1, UUID.randomUUID(), "SKU-1", "L1", "A-01", LocalDate.of(2027, 1, 1), 10, null,
                        null, null, null, null, null),
                new CycleCountLine(2, UUID.randomUUID(), "SKU-1", "L2", "A-02", null, 5, null, null, null, null,
                        null, null)), "staff", NOW, null, null, null, NOW, 0);
    }

    @Test
    void everyLineCountedMakesTheCountCountedWithItsVariance() {
        CycleCount partly = count().count(Map.of(1, new CycleCount.Entry(8, CycleCountReason.LOST, null)), "u", NOW);
        assertThat(partly.status()).isEqualTo(CycleCountStatus.OPEN);
        assertThat(partly.countedLines()).isEqualTo(1);
        CycleCount all = partly.count(Map.of(2, new CycleCount.Entry(6, null, null)), "u", NOW);
        assertThat(all.status()).isEqualTo(CycleCountStatus.COUNTED);
        assertThat(all.netVariance()).isEqualTo(-1);
        assertThat(all.varianceLines()).isEqualTo(2);
    }

    @Test
    void postingNeedsEveryLineCountedAndAReasonPerVariance() {
        CycleCount c = count().count(Map.of(1, new CycleCount.Entry(10, null, null)), "u", NOW);
        assertThatThrownBy(c::requirePostable).isInstanceOf(InventoryRuleViolation.class)
                .hasMessageContaining("Count every line");
        CycleCount noReason = c.count(Map.of(2, new CycleCount.Entry(7, null, null)), "u", NOW);
        assertThatThrownBy(noReason::requirePostable).isInstanceOf(InventoryRuleViolation.class);
        noReason.count(Map.of(2, new CycleCount.Entry(7, CycleCountReason.FOUND, "Behind pallet")), "u", NOW)
                .requirePostable();
    }

    @Test
    void unknownLinesAndPostedCountsAreRefused() {
        assertThatThrownBy(() -> count().count(Map.of(9, new CycleCount.Entry(1, null, null)), "u", NOW))
                .isInstanceOf(InventoryRuleViolation.class);
        CycleCount posted = count().posted(count().lines(), "u", NOW);
        assertThatThrownBy(() -> posted.cancelled(NOW)).isInstanceOf(InventoryStateConflict.class);
    }

    @Test
    void anExpectedBatchCountsCasesAndChecksItsDates() {
        ExpectedBatch b = new ExpectedBatch(6, " B-1 ", LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 1));
        assertThat(b.cases(12)).isEqualTo(2);
        assertThat(b.cases(13)).isEqualTo(3);
        assertThat(b.lotNo()).isEqualTo("B-1");
        assertThat(ExpectedBatch.NONE.present()).isFalse();
        assertThatThrownBy(() -> new ExpectedBatch(null, null, LocalDate.of(2027, 1, 1), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(InventoryRuleViolation.class);
        assertThatThrownBy(() -> new ExpectedBatch(0, null, null, null)).isInstanceOf(InventoryRuleViolation.class);
    }
}
