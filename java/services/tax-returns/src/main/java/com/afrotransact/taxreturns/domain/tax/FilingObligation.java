package com.afrotransact.taxreturns.domain.tax;

import java.time.YearMonth;

/**
 * One month's tax for one jurisdiction: what the ledger says is due, what was remitted and where that leaves us.
 *
 * @param dueMinor              net liability now (collected − refunded)
 * @param remittance            the active remittance, or null
 * @param outstandingMinor      due now − paid (positive: still owed, negative: overpaid)
 * @param changedSinceFilingMinor due now − due when the remittance was recorded (0 without a remittance)
 */
public record FilingObligation(YearMonth month, String jurisdiction, String currency, long dueMinor,
                               TaxRemittance remittance, FilingStatus status, long outstandingMinor,
                               long changedSinceFilingMinor) {

    public static FilingObligation of(YearMonth month, String jurisdiction, String currency, long dueMinor,
                                      TaxRemittance remittance) {
        if (remittance == null) {
            return new FilingObligation(month, jurisdiction, currency, dueMinor, null,
                    dueMinor > 0 ? FilingStatus.NOT_FILED : FilingStatus.NOTHING_DUE, Math.max(dueMinor, 0), 0);
        }
        long outstanding = dueMinor - remittance.paidMinor();
        FilingStatus status = outstanding == 0 ? FilingStatus.SETTLED
                : outstanding > 0 ? FilingStatus.OWES_MORE : FilingStatus.OVERPAID;
        return new FilingObligation(month, jurisdiction, currency, dueMinor, remittance, status, outstanding,
                dueMinor - remittance.dueMinor());
    }
}
