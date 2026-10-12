package com.afrotransact.payments.application;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.afrotransact.payments.domain.FeeSchedule;
import com.afrotransact.payments.domain.NegotiatedRate;
import com.afrotransact.payments.domain.OrderLine;
import com.afrotransact.payments.port.NegotiatedRateRepository;
import org.springframework.stereotype.Component;

/**
 * Snapshots the commission / connection-fee rate of every line of a new payment: resolved once, under the fee-schedule
 * version of the order's placement time, with the organisations' plans and negotiated rates of that moment. Captures,
 * refunds and settlements later read the snapshot, so changing a plan, a negotiated rate or the fee schedule never
 * re-prices a placed order or booking.
 */
@Component
class LineRates {

    private final NegotiatedRateRepository negotiated;
    private final PaymentsSettings settings;

    LineRates(NegotiatedRateRepository negotiated, PaymentsSettings settings) {
        this.negotiated = negotiated;
        this.settings = settings;
    }

    /** The organisations selling on {@code lines} (whose plans the caller looks up before the transaction). */
    static Set<String> orgsOf(List<OrderLine> lines) {
        Set<String> out = new LinkedHashSet<>();
        lines.forEach(l -> out.add(l.sellerOrg()));
        return out;
    }

    /**
     * @param plans plan code per organisation (organisations without a plan are absent)
     */
    List<OrderLine> snapshot(FeeSchedule schedule, List<OrderLine> lines, Instant placedAt, Map<String, String> plans) {
        List<NegotiatedRate> rates = negotiated.forOrgs(orgsOf(lines));
        return lines.stream().map(l -> l.withRate(schedule.rateFor(l.kind(), l.sellerOrg(), l.category(),
                plans.get(l.sellerOrg()), rates, placedAt, settings.retailOrgId()))).toList();
    }
}
