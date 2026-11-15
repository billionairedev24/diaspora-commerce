package com.afrotransact.orders.adapter.out.module;

import java.util.List;
import java.util.Optional;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.orders.port.MemberBenefits;
import com.afrotransact.orders.port.UpstreamFailure;
import com.afrotransact.payments.api.MembershipBenefitsApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link MemberBenefits} through payments' {@link MembershipBenefitsApi} inside this JVM (mode {@code in-process}). As over
 * HTTP the answer is advisory: when payments cannot answer, a shopper gets no early access and pays the delivery fees.
 */
@Component
@ConditionalOnExpression("'${at.orders.payments.mode:${at.orders.mode:http}}' == 'in-process'")
class InProcessMemberBenefits implements MemberBenefits {

    private static final Logger LOG = LoggerFactory.getLogger(InProcessMemberBenefits.class);
    private static final String SERVICE = "payments";

    private final ObjectProvider<MembershipBenefitsApi> api;
    private final ModuleUpstream upstream;

    InProcessMemberBenefits(ObjectProvider<MembershipBenefitsApi> api, ModuleUpstream upstream,
                            ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(MembershipBenefitsApi.class, true, false).length > 0,
                "orders", "payments", "at.orders.payments.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public int earlyAccessHours(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            return 0;
        }
        try {
            return upstream.call(SERVICE, () -> api.getObject().earlyAccessHours(customerId));
        } catch (UpstreamFailure f) {
            LOG.warn("Membership entitlements unavailable ({}); no early access for this order", f.code());
            return 0;
        }
    }

    @Override
    public Optional<Waivers> waivers(String customerId, long itemsMinor, String currency, List<Fee> fees) {
        if (customerId == null || customerId.isBlank() || fees.isEmpty()) {
            return Optional.empty();
        }
        try {
            MembershipBenefitsApi.WaiverQuoteInfo answer = upstream.call(SERVICE, () -> api.getObject().quote(customerId,
                    itemsMinor, currency, fees.stream().map(f -> new MembershipBenefitsApi.Fee(f.key(), f.kind().name(),
                    f.feeMinor())).toList()));
            if (!answer.member()) {
                return Optional.empty();
            }
            List<Waiver> waived = answer.waivers().stream().filter(w -> w.waivedMinor() > 0)
                    .map(w -> new Waiver(w.key(), w.benefit(), w.waivedMinor())).toList();
            return waived.isEmpty() ? Optional.empty() : Optional.of(new Waivers(answer.tierCode(),
                    answer.tierName() == null ? answer.tierCode() : answer.tierName(), waived));
        } catch (UpstreamFailure f) {
            LOG.warn("Membership benefits unavailable ({}); the customer pays the delivery fees", f.code());
            return Optional.empty();
        }
    }
}
