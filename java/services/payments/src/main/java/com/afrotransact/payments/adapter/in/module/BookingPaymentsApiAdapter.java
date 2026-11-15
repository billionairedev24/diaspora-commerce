package com.afrotransact.payments.adapter.in.module;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.payments.api.BookingPaymentsApi;
import com.afrotransact.payments.application.CaptureCommand;
import com.afrotransact.payments.application.CreatePaymentCommand;
import com.afrotransact.payments.application.FeeScheduleService;
import com.afrotransact.payments.application.OffSessionChargeService;
import com.afrotransact.payments.application.OffSessionCommand;
import com.afrotransact.payments.application.PaymentResult;
import com.afrotransact.payments.application.PaymentService;
import com.afrotransact.payments.application.RefundCommand;
import com.afrotransact.payments.application.SettlementService;
import com.afrotransact.payments.domain.EffectiveFees;
import com.afrotransact.payments.domain.FeeKind;
import com.afrotransact.payments.domain.Fulfilment;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.OrderLine;
import com.afrotransact.payments.domain.SettlementKind;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link BookingPaymentsApi}: the use cases behind the payment, fee and settlement endpoints bookings
 * calls, entered under the calling module's principal. The services check {@code payments:service} as for a token; booking
 * lines are built the way the HTTP request carries them (kind SERVICE, self-fulfilled, one unit, no shipping, no tax).
 */
@Component
class BookingPaymentsApiAdapter implements BookingPaymentsApi {

    private final PaymentService payments;
    private final OffSessionChargeService offSession;
    private final FeeScheduleService fees;
    private final SettlementService settlements;
    private final ModuleEntry entry;

    BookingPaymentsApiAdapter(PaymentService payments, OffSessionChargeService offSession, FeeScheduleService fees,
                              SettlementService settlements, ActorResolver actors) {
        this.payments = payments;
        this.offSession = offSession;
        this.fees = fees;
        this.settlements = settlements;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public BookingPayment create(NewBookingPayment r, String idempotencyKey) {
        return entry.run(() -> {
            String currency = r.currency();
            Money zero = Money.of(0, currency);
            List<OrderLine> lines = r.lines().stream().map(l -> new OrderLine(l.lineId(), r.sellerOrg(), r.category(),
                    Fulfilment.SELF, 1, Money.of(l.amountMinor(), currency), zero, zero, null, zero, zero,
                    FeeKind.of("SERVICE", r.category()), null)).toList();
            PaymentResult result = payments.create(new CreatePaymentCommand(r.orderId(), null,
                    Money.of(r.amountMinor(), currency), List.of(), r.placedAt(), r.metadata(), lines, r.shopperId(),
                    r.saveForOffSession(), false, false, List.of()), idempotencyKey);
            return BookingViews.of(result.payment());
        }, PaymentsFailures::map);
    }

    @Override
    public BookingPayment get(UUID paymentId, boolean refresh) {
        return entry.run(() -> BookingViews.of(payments.get(paymentId, refresh)), PaymentsFailures::map);
    }

    @Override
    public void capture(UUID paymentId, String captureId, List<String> lineIds, long amountMinor, boolean finalCapture,
                        String idempotencyKey) {
        entry.runVoid(() -> {
            Money amount = Money.of(amountMinor, payments.get(paymentId, false).currency());
            payments.capture(paymentId, new CaptureCommand(captureId, lineIds, amount, finalCapture), idempotencyKey);
        }, PaymentsFailures::map);
    }

    @Override
    public void cancel(UUID paymentId, String reason, String idempotencyKey) {
        entry.runVoid(() -> payments.cancel(paymentId, reason, idempotencyKey), PaymentsFailures::map);
    }

    @Override
    public BookingPayment release(UUID paymentId, String reason, String idempotencyKey) {
        return entry.run(() -> BookingViews.of(payments.release(paymentId, reason, idempotencyKey).payment()),
                PaymentsFailures::map);
    }

    @Override
    public BookingPayment chargeOffSession(UUID paymentId, String customerId, String paymentMethodId,
                                           List<String> lineIds, String idempotencyKey) {
        return entry.run(() -> BookingViews.of(offSession.charge(paymentId, new OffSessionCommand(customerId,
                paymentMethodId, lineIds), idempotencyKey).payment()), PaymentsFailures::map);
    }

    @Override
    public void refund(UUID paymentId, String lineId, long amountMinor, String reason, String idempotencyKey) {
        entry.runVoid(() -> {
            String currency = payments.get(paymentId, false).currency();
            payments.refund(paymentId, new RefundCommand(List.of(new RefundCommand.Line(lineId,
                    Money.of(amountMinor, currency), Money.of(0, currency), Money.of(0, currency))), reason, null, false,
                    null), idempotencyKey);
        }, PaymentsFailures::map);
    }

    @Override
    public void serviceCompleted(String orderId, Instant completedAt) {
        entry.runVoid(() -> settlements.markDelivered(orderId, completedAt, SettlementKind.SERVICE),
                PaymentsFailures::map);
    }

    @Override
    public ConnectionFees connectionFees(String category, String orgId) {
        return entry.run(() -> {
            EffectiveFees f = fees.effective(FeeKind.SERVICE, category, orgId, null);
            return new ConnectionFees(f.commissionBps(), f.commissionSource(), f.cardPctBps(), f.cardFixed().minor(),
                    f.version(), f.currency(), f.kind().name());
        }, PaymentsFailures::map);
    }
}
