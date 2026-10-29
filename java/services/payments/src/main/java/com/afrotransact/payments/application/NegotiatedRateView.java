package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.NegotiatedRate;
import com.afrotransact.payments.domain.NegotiatedRateAudit;
import com.afrotransact.payments.domain.NegotiatedRateStatus;

/** A negotiated rate with its status now and (for one rate) its audit trail. */
public record NegotiatedRateView(NegotiatedRate rate, NegotiatedRateStatus status, List<NegotiatedRateAudit> audit) {

    public NegotiatedRateView {
        audit = audit == null ? List.of() : List.copyOf(audit);
    }
}
