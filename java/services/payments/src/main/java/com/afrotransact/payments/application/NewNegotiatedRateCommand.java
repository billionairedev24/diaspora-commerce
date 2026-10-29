package com.afrotransact.payments.application;

import java.time.Instant;

import com.afrotransact.payments.domain.NegotiatedRateType;

/**
 * A negotiated commission or connection fee for one organisation.
 *
 * @param category     one category, or null / {@code *} for every category
 * @param startsAt     null = now; never in the past
 * @param endsAt       null = open-ended
 * @param reason       required (kept in the audit trail)
 * @param negotiatedBy the staff member who negotiated it; null = the caller
 * @param reference    contract or ticket reference, optional
 * @param amountMinor  per-unit amount of an FBA fee (minor units); null for rates in basis points
 */
public record NewNegotiatedRateCommand(String orgId, NegotiatedRateType type, String category, int rateBps,
                                       Instant startsAt, Instant endsAt, String reason, String negotiatedBy,
                                       String reference, Long amountMinor) {

    /** A negotiated rate in basis points. */
    public NewNegotiatedRateCommand(String orgId, NegotiatedRateType type, String category, int rateBps,
                                    Instant startsAt, Instant endsAt, String reason, String negotiatedBy,
                                    String reference) {
        this(orgId, type, category, rateBps, startsAt, endsAt, reason, negotiatedBy, reference, null);
    }
}
