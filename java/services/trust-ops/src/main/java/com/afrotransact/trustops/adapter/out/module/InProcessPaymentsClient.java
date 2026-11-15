package com.afrotransact.trustops.adapter.out.module;

import java.util.List;
import java.util.UUID;

import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleMode;
import com.afrotransact.payments.api.ClaimRefundApi;
import com.afrotransact.platform.web.module.ConditionalOnModuleMode;
import com.afrotransact.trustops.port.PaymentsClient;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentsClient} through payments' published {@link ClaimRefundApi} inside this JVM (mode {@code in-process}), entered
 * as trust-ops' caller principal (scope {@code payments:service}). The refund key is passed through, so a claim retried after a
 * crash never refunds twice; refusals keep payments' status and code in the {@code UpstreamException}.
 */
@Component
@ConditionalOnModuleMode(caller = "trust-ops", callee = "payments", value = ModuleMode.IN_PROCESS)
class InProcessPaymentsClient implements PaymentsClient {

    private static final String SERVICE = "payments";

    private final ObjectProvider<ClaimRefundApi> api;
    private final ModuleUpstream upstream;

    InProcessPaymentsClient(ObjectProvider<ClaimRefundApi> api, ModuleUpstream upstream, ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(ClaimRefundApi.class, true, false).length > 0, "trust-ops",
                "payments", "at.trustops.payments.mode");
        this.api = api;
        this.upstream = upstream;
    }

    @Override
    public RefundResult refund(UUID paymentId, List<RefundLine> lines, String reason, String idempotencyKey) {
        return result(upstream.call(SERVICE, () -> api.getObject().refund(paymentId, lines.stream()
                .map(l -> new ClaimRefundApi.RefundAmount(l.lineId(), l.itemsMinor(), l.shippingMinor(), l.taxMinor()))
                .toList(), reason, idempotencyKey)));
    }

    @Override
    public RefundResult refundByOrder(String orderId, List<String> lineIds, long amountMinor, String reason,
                                      String idempotencyKey) {
        return result(upstream.call(SERVICE, () -> api.getObject().refundByOrder(orderId, lineIds, amountMinor, reason,
                idempotencyKey)));
    }

    private static RefundResult result(ClaimRefundApi.ClaimRefund r) {
        return new RefundResult(r.refundId(), r.amountMinor(), r.status(), r.replayed());
    }
}
