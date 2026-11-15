package com.afrotransact.payments.adapter.in.module;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.api.OrderPaymentsApi;
import com.afrotransact.payments.application.CaptureCommand;
import com.afrotransact.payments.application.CaptureResult;
import com.afrotransact.payments.application.CreatePaymentCommand;
import com.afrotransact.payments.application.FeeScheduleService;
import com.afrotransact.payments.application.MembershipBenefitsService.AppliedWaiver;
import com.afrotransact.payments.application.OrderSplit;
import com.afrotransact.payments.application.PaymentResult;
import com.afrotransact.payments.application.PaymentService;
import com.afrotransact.payments.application.RefundCommand;
import com.afrotransact.payments.application.RefundResult;
import com.afrotransact.payments.application.SettlementService;
import com.afrotransact.payments.application.SplitService;
import com.afrotransact.payments.domain.BenefitCode;
import com.afrotransact.payments.domain.DeliveryQuote;
import com.afrotransact.payments.domain.FeeKind;
import com.afrotransact.payments.domain.Fulfilment;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.OrderLine;
import com.afrotransact.payments.domain.RecipientShare;
import com.afrotransact.payments.domain.SettlementKind;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link OrderPaymentsApi}: the use cases behind the payment, delivery-pricing, settlement and split
 * endpoints, entered under the calling module's principal. The application services apply the permission checks
 * ({@code payments:service}) exactly as for a token; the request is built the way the controllers build theirs.
 */
@Component
class OrderPaymentsApiAdapter implements OrderPaymentsApi {

    private final PaymentService payments;
    private final FeeScheduleService fees;
    private final SettlementService settlements;
    private final SplitService splits;
    private final ModuleEntry entry;

    OrderPaymentsApiAdapter(PaymentService payments, FeeScheduleService fees, SettlementService settlements,
                            SplitService splits, ActorResolver actors) {
        this.payments = payments;
        this.fees = fees;
        this.settlements = settlements;
        this.splits = splits;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public PaymentInfo create(NewPayment r, String idempotencyKey) {
        return entry.run(() -> {
            String currency = r.currency();
            PaymentResult result = payments.create(new CreatePaymentCommand(r.orderId(), null,
                    Money.of(r.amountMinor(), currency), r.paymentMethodTypes(), r.placedAt(), r.metadata(),
                    r.lines().stream().map(l -> line(l, currency)).toList(), r.shopperId(), r.saveForOffSession(),
                    r.offSession(), r.useCredit(), r.waivers().stream().map(w -> new AppliedWaiver(w.ref(),
                    BenefitCode.valueOf(w.benefit()), w.amountMinor(), w.tierCode())).toList()), idempotencyKey);
            return PaymentViews.of(result.payment());
        }, PaymentsFailures::map);
    }

    @Override
    public PaymentInfo get(String paymentId, boolean refresh) {
        return entry.run(() -> PaymentViews.of(payments.get(UUID.fromString(paymentId), refresh)), PaymentsFailures::map);
    }

    @Override
    public Optional<PaymentInfo> findByOrder(String orderId) {
        return entry.run(() -> {
            try {
                return Optional.of(PaymentViews.of(payments.getByOrder(orderId)));
            } catch (NotFoundException notFound) {
                return Optional.<PaymentInfo>empty();
            }
        }, PaymentsFailures::map);
    }

    @Override
    public void cancel(String paymentId, String reason, String idempotencyKey) {
        entry.runVoid(() -> payments.cancel(UUID.fromString(paymentId), reason, idempotencyKey), PaymentsFailures::map);
    }

    @Override
    public CaptureInfo capture(String paymentId, OrderPaymentsApi.CaptureRequest r, String idempotencyKey) {
        return entry.run(() -> {
            UUID id = UUID.fromString(paymentId);
            Money amount = Money.of(r.amountMinor(), payments.get(id, false).currency());
            CaptureResult result = payments.capture(id, new CaptureCommand(r.shipmentId(), r.lineIds(), amount,
                    r.finalCapture()), idempotencyKey);
            return new CaptureInfo(String.valueOf(result.capture().id()), result.capture().amount().minor(),
                    result.capture().lineIds());
        }, PaymentsFailures::map);
    }

    @Override
    public String refund(String paymentId, String idempotencyKey, List<RefundLine> lines, String reason) {
        return entry.run(() -> {
            UUID id = UUID.fromString(paymentId);
            String currency = payments.get(id, false).currency();
            RefundResult result = payments.refund(id, new RefundCommand(lines.stream()
                    .map(l -> new RefundCommand.Line(l.lineId(), Money.of(l.itemsMinor(), currency),
                            Money.of(l.shippingMinor(), currency), Money.of(l.taxMinor(), currency))).toList(),
                    reason, null, false, null), idempotencyKey);
            return String.valueOf(result.refund().id());
        }, PaymentsFailures::map);
    }

    @Override
    public void delivered(String orderId, Instant deliveredAt) {
        entry.runVoid(() -> settlements.markDelivered(orderId, deliveredAt, SettlementKind.PRODUCT),
                PaymentsFailures::map);
    }

    @Override
    public void shipmentDelivered(String orderId, String shipmentId, Instant deliveredAt) {
        entry.runVoid(() -> settlements.markShipmentDelivered(orderId, shipmentId, deliveredAt, SettlementKind.PRODUCT),
                PaymentsFailures::map);
    }

    @Override
    public SplitInfo split(String orderId) {
        return entry.run(() -> {
            OrderSplit s = splits.splitOf(orderId);
            return new SplitInfo(orderId, s.split().charge().minor(), s.split().stripeFee().minor(),
                    s.split().totalTax().minor(), s.split().currency(),
                    s.split().recipients().stream().map(OrderPaymentsApiAdapter::recipient).toList());
        }, PaymentsFailures::map);
    }

    @Override
    public DeliveryQuoteInfo quoteDelivery(String sellerOrgId, double distanceMiles, int packages) {
        return entry.run(() -> {
            DeliveryQuote q = fees.quoteDelivery(sellerOrgId, BigDecimal.valueOf(distanceMiles), packages, null);
            return new DeliveryQuoteInfo(q.available(), q.unavailableReason(), q.customerCharge().minor(),
                    q.sellerFee().minor(), q.scheduleVersion());
        }, PaymentsFailures::map);
    }

    private static OrderLine line(OrderPaymentsApi.Line l, String currency) {
        return new OrderLine(l.lineId(), l.sellerOrg(), l.category(), Fulfilment.valueOf(l.fulfilment()), l.quantity(),
                Money.of(l.itemsMinor(), currency), Money.of(l.shippingMinor(), currency),
                Money.of(l.taxMinor(), currency), l.taxJurisdiction(), Money.of(l.shippingTaxMinor(), currency),
                Money.of(l.deliveryFeeMinor(), currency), FeeKind.of(null, l.category()), null);
    }

    private static OrderPaymentsApi.Recipient recipient(RecipientShare r) {
        return new OrderPaymentsApi.Recipient(r.recipientId(), r.kind().name(), r.items().minor(), r.shipping().minor(),
                r.tax().minor(), r.commission().minor(), r.fbaFees().minor(), r.deliveryFees().minor(),
                r.stripeFee().minor(), r.net().minor());
    }
}
