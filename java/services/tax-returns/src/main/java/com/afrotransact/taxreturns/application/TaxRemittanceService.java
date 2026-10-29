package com.afrotransact.taxreturns.application;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.afrotransact.taxreturns.domain.tax.FilingObligation;
import com.afrotransact.taxreturns.domain.tax.TaxRemittance;

/**
 * The record of what AfroTransact filed and paid to each state: for every month and jurisdiction with marketplace
 * tax, what the ledger says is due, what was remitted and what is left. Staff record the filing after they pay the
 * state; a mistake is voided with a reason and recorded again.
 */
public interface TaxRemittanceService {

    /** The last {@code months} months up to and including {@code through}, newest first (tax:read). */
    List<FilingObligation> obligations(YearMonth through, int months);

    /** One obligation (tax:read). */
    FilingObligation obligation(YearMonth month, String jurisdiction, String currency);

    /** Every record of a month, voided ones included, newest first (tax:read). */
    List<TaxRemittance> history(YearMonth month);

    /**
     * Records a filing and payment (tax:configure). 409 {@code tax.remittance-exists} when the month and jurisdiction
     * already have an active one; 422 for a future month, a future date or a negative amount.
     */
    TaxRemittance record(RecordRemittance command);

    /** Withdraws a mistaken record (tax:configure); a reason of at least 10 characters is required. */
    TaxRemittance voidRemittance(UUID id, String reason);

    /**
     * @param jurisdiction state jurisdiction as in the liability report (US-TX)
     * @param paidMinor    what was paid to the state, in minor units (0 for a nil return)
     */
    record RecordRemittance(YearMonth month, String jurisdiction, String currency, long paidMinor, LocalDate filedOn,
                            LocalDate paidOn, String confirmationRef, String paymentRef, String note) {
    }
}
