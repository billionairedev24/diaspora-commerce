package com.afrotransact.taxreturns.adapter.out.module;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.payments.api.ReturnRefundApi;
import com.afrotransact.taxreturns.domain.returns.RefundLineAmount;
import com.afrotransact.taxreturns.port.PaymentsGateway;
import com.afrotransact.taxreturns.port.UpstreamException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentsGateway} through payments' published {@link ReturnRefundApi} inside this JVM (mode {@code in-process}): the
 * same reads and refunds as {@code GET /v1/payments?orderId=} and {@code POST /v1/payments/{id}/refunds}, entered as tax-returns'
 * caller principal (scope {@code payments:service}). A payment that does not exist is an empty answer (404 over HTTP); other
 * failures are the HTTP adapter's {@code UpstreamException}s (status and problem code of the refusal).
 */
@Component
@ConditionalOnExpression("'${at.taxreturns.payments.mode:${at.taxreturns.mode:http}}' == 'in-process'")
class InProcessPaymentsGateway implements PaymentsGateway {

    private final ModuleUpstreams<ReturnRefundApi> payments;

    InProcessPaymentsGateway(ObjectProvider<ReturnRefundApi> api, ObjectProvider<CallerPrincipals> callers,
                             ListableBeanFactory beans) {
        this.payments = new ModuleUpstreams<>(ReturnRefundApi.class, "payments", api, callers, beans);
    }

    @Override
    public Optional<ChargedPayment> paymentForOrder(UUID orderId) {
        Optional<ReturnRefundApi.ChargedPayment> found;
        try {
            found = payments.call(api -> api.paymentForOrder(orderId));
        } catch (UpstreamException ex) {
            if (ex.status() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
        return found.map(p -> new ChargedPayment(p.paymentId(), p.currency(), p.lines().stream()
                .map(l -> new ChargedLine(l.lineId(), l.sellerOrg(), l.quantity(), l.itemsMinor(), l.shippingMinor(),
                        l.taxMinor(), l.captured(), l.refundedItemsMinor(), l.refundedShippingMinor(),
                        l.refundedTaxMinor())).toList()));
    }

    @Override
    public IssuedRefund refund(UUID paymentId, List<RefundLineAmount> lines, String reason, String idempotencyKey) {
        String why = reason == null ? "Return" : reason.length() > 500 ? reason.substring(0, 500) : reason;
        ReturnRefundApi.IssuedRefund r = payments.call(api -> api.refund(paymentId, lines.stream()
                .map(l -> new ReturnRefundApi.RefundAmount(l.lineId(), l.itemsMinor(), l.shippingMinor(), l.taxMinor()))
                .toList(), why, idempotencyKey));
        return new IssuedRefund(r.id(), r.status(), r.amountMinor());
    }
}
