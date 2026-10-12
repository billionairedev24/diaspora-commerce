package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.domain.AppliedRate;
import com.afrotransact.payments.domain.FeeKind;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.NegotiatedRate;

/**
 * What one organisation pays on {@code kind} lines at {@code at}: the rate of every category the fee schedule, its plan
 * or its negotiated rates name, the rate of any other category, and its negotiated rates (Seller Center's fees page,
 * Provider Hub, the staff rate calculator).
 *
 * @param planCode   the organisation's plan (null when none or unknown)
 * @param otherRate  the rate of a category nobody lists (category "")
 * @param negotiated the organisation's negotiated rates of this kind that are active or scheduled at {@code at}
 */
public record RateCard(String orgId, FeeKind kind, UUID scheduleId, int version, Instant effectiveFrom, Instant at,
                       String currency, String planCode, AppliedRate otherRate, List<AppliedRate> categories,
                       List<NegotiatedRate> negotiated, int cardPctBps, Money cardFixed, Money fbaPerUnit,
                       boolean reverseOnRefund, boolean retail) {

    public RateCard {
        categories = List.copyOf(categories);
        negotiated = List.copyOf(negotiated);
    }
}
