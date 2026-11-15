package com.afrotransact.payments.adapter.in.module;

import java.util.List;

import com.afrotransact.payments.api.MembershipBenefitsApi;
import com.afrotransact.payments.application.MembershipBenefitsService;
import com.afrotransact.payments.application.MembershipBenefitsService.Entitlements;
import com.afrotransact.payments.application.MembershipBenefitsService.WaiverQuote;
import com.afrotransact.payments.domain.FeeWaivers;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link MembershipBenefitsApi}: {@code GET /v1/memberships/customers/{id}/entitlements} and
 * {@code POST /v1/memberships/waivers} under the calling module's principal ({@code payments:service} is checked by the
 * service as for a token).
 */
@Component
class MembershipBenefitsApiAdapter implements MembershipBenefitsApi {

    private final MembershipBenefitsService benefits;
    private final ModuleEntry entry;

    MembershipBenefitsApiAdapter(MembershipBenefitsService benefits, ActorResolver actors) {
        this.benefits = benefits;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public int earlyAccessHours(String customerId) {
        return entry.run(() -> {
            Entitlements e = benefits.entitlementsOf(customerId);
            return e == null || !e.member() ? 0 : Math.max(0, e.earlyAccessHours());
        }, PaymentsFailures::map);
    }

    @Override
    public WaiverQuoteInfo quote(String customerId, long itemsMinor, String currency, List<Fee> fees) {
        return entry.run(() -> {
            WaiverQuote q = benefits.quote(customerId, itemsMinor, currency, fees.stream()
                    .map(f -> new FeeWaivers.Fee(f.key(), FeeWaivers.Kind.valueOf(f.kind()), f.feeMinor())).toList());
            return new WaiverQuoteInfo(q.member(), q.tierCode(), q.tierName(), q.waivers().stream()
                    .map(w -> new MembershipBenefitsApi.Waiver(w.key(), w.benefit().name(), w.waivedMinor())).toList());
        }, PaymentsFailures::map);
    }
}
