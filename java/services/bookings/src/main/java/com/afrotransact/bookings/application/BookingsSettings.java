package com.afrotransact.bookings.application;

import java.time.Duration;
import java.util.Objects;

import com.afrotransact.bookings.domain.BalanceChargePolicy;
import com.afrotransact.bookings.domain.FeeSchedule;

/**
 * Business settings of the booking flow (from {@code at.bookings.*}).
 *
 * @param holdTtl                how long a slot is held while the customer authorises the deposit
 * @param requestSla             providers must answer a request within this time, or it expires
 * @param autoCompleteAfter      confirmed bookings auto-complete this long after they end
 * @param lateProviderFeeMinor   fee for provider cancellations under 24 h
 * @param manageUrlBase          customer link to manage a booking (reference is appended)
 * @param balance                long-dated bookings: balance release and off-session charge
 * @param fallbackFees           fees used while payments' fee schedule cannot be read
 * @param feesCacheTtl           how long payments' effective fees are reused per organisation
 * @param retailOrgId            AfroTransact Retail, the first-party merchant of record: never charged commission
 *                               (not even by the fallback), a late-cancellation fee or a reliability strike
 */
public record BookingsSettings(Duration holdTtl, Duration requestSla, Duration autoCompleteAfter,
                               long lateProviderFeeMinor, String manageUrlBase, BalanceChargePolicy balance,
                               FeeSchedule fallbackFees, Duration feesCacheTtl, String retailOrgId) {

    public BookingsSettings {
        Objects.requireNonNull(holdTtl, "holdTtl");
        Objects.requireNonNull(requestSla, "requestSla");
        Objects.requireNonNull(autoCompleteAfter, "autoCompleteAfter");
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(fallbackFees, "fallbackFees");
        Objects.requireNonNull(feesCacheTtl, "feesCacheTtl");
        Objects.requireNonNull(retailOrgId, "retailOrgId");
        manageUrlBase = manageUrlBase == null ? "" : manageUrlBase;
    }

    public boolean isRetail(String orgId) {
        return retailOrgId.equals(orgId);
    }

    /** Late provider-cancellation fee of an organisation: none for AfroTransact Retail. */
    public long lateProviderFeeFor(String orgId) {
        return isRetail(orgId) ? 0 : lateProviderFeeMinor;
    }
}
