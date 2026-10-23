package com.afrotransact.taxreturns.domain.tax;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class FilingObligationTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 9);

    private static TaxRemittance paid(long due, long paid) {
        return new TaxRemittance(UUID.randomUUID(), "US-TX", MONTH, "USD", due, paid, LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 10, 15), null, null, null, "staff_1", Instant.parse("2026-10-15T12:00:00Z"), null,
                null, null);
    }

    @Test
    void nothingRecordedIsNotFiledWhenTaxIsDueAndNothingDueOtherwise() {
        FilingObligation due = FilingObligation.of(MONTH, "US-TX", "USD", 1_336, null);
        assertThat(due.status()).isEqualTo(FilingStatus.NOT_FILED);
        assertThat(due.outstandingMinor()).isEqualTo(1_336);

        assertThat(FilingObligation.of(MONTH, "US-TX", "USD", 0, null).status()).isEqualTo(FilingStatus.NOTHING_DUE);
        FilingObligation refundedMonth = FilingObligation.of(MONTH, "US-TX", "USD", -50, null);
        assertThat(refundedMonth.status()).isEqualTo(FilingStatus.NOTHING_DUE);
        assertThat(refundedMonth.outstandingMinor()).isZero();
    }

    @Test
    void aRemittanceIsComparedWithWhatTheLedgerSaysNow() {
        assertThat(FilingObligation.of(MONTH, "US-TX", "USD", 1_336, paid(1_336, 1_336)).status())
                .isEqualTo(FilingStatus.SETTLED);

        FilingObligation late = FilingObligation.of(MONTH, "US-TX", "USD", 1_500, paid(1_336, 1_336));
        assertThat(late.status()).isEqualTo(FilingStatus.OWES_MORE);
        assertThat(late.outstandingMinor()).isEqualTo(164);
        assertThat(late.changedSinceFilingMinor()).isEqualTo(164);

        FilingObligation over = FilingObligation.of(MONTH, "US-TX", "USD", 800, paid(1_336, 1_336));
        assertThat(over.status()).isEqualTo(FilingStatus.OVERPAID);
        assertThat(over.outstandingMinor()).isEqualTo(-536);
        assertThat(over.changedSinceFilingMinor()).isEqualTo(-536);
    }

    @Test
    void payingLessThanWasDueAtFilingStaysOwed() {
        FilingObligation shortPaid = FilingObligation.of(MONTH, "US-TX", "USD", 1_336, paid(1_336, 1_000));
        assertThat(shortPaid.status()).isEqualTo(FilingStatus.OWES_MORE);
        assertThat(shortPaid.outstandingMinor()).isEqualTo(336);
        assertThat(shortPaid.changedSinceFilingMinor()).isZero();
    }
}
