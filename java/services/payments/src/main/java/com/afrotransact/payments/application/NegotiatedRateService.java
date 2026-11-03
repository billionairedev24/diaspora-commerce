package com.afrotransact.payments.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.NegotiatedRateStatus;
import com.afrotransact.payments.domain.NegotiatedRateType;

/**
 * Negotiated commission and connection-fee rates (staff console › Fees & commissions › Negotiated rates and a seller's
 * detail): created and ended by Super Admin / Finance (payments:configure) with a required reason, audited, expired
 * by a job at their end date. They win over plan, category and default rates while active.
 */
public interface NegotiatedRateService {

    /**
     * Creates a rate. Refused for AfroTransact Retail ({@code 422 payments.retail-no-fees}) and when another rate of
     * the organisation, type and category overlaps its period ({@code 409 payments.negotiated-rate-overlap}).
     */
    NegotiatedRateView create(NewNegotiatedRateCommand command);

    /** Ends a scheduled or active rate now, with a reason ({@code 409 payments.negotiated-rate-ended} twice). */
    NegotiatedRateView end(UUID id, String reason);

    /** One rate with its audit trail (staff readers, or members of its organisation). */
    NegotiatedRateView get(UUID id);

    /**
     * Rates newest first: optionally one organisation, type and status. Staff readers see every organisation;
     * organisation members only their own (orgId is then their organisation).
     */
    List<NegotiatedRateView> list(String orgId, NegotiatedRateType type, NegotiatedRateStatus status, int limit);

    /** Records the expiry of rates past their end date (job); returns how many expired. */
    int expireDue();
}
