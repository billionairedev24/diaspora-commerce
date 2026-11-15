package com.afrotransact.bookings.adapter.out.module;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.afrotransact.bookings.port.PaymentsClient;
import com.afrotransact.bookings.port.PaymentsClientException;
import com.afrotransact.module.CallerPrincipal;
import com.afrotransact.module.CallerPrincipals;
import com.afrotransact.module.InProcessCalls;
import com.afrotransact.module.ModuleApiException;
import com.afrotransact.payments.api.BookingPaymentsApi;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * {@link PaymentsClient} through payments' published {@link BookingPaymentsApi} inside this JVM (mode {@code in-process}),
 * entered as bookings' caller principal (scope {@code payments:service}). Same results as the HTTP client and the same
 * {@link PaymentsClientException}: a refusal keeps payments' status and problem code (a decline is 402, a missing scope 403),
 * anything else is {@code status 0}, "unavailable". Same default reasons as the HTTP client.
 */
@Component
@ConditionalOnExpression("'${at.bookings.payments.mode:${at.bookings.mode:http}}' == 'in-process'")
class InProcessPaymentsClient implements PaymentsClient {

    private static final String MODULE = "bookings";

    private final ObjectProvider<BookingPaymentsApi> api;
    private final ObjectProvider<CallerPrincipals> callers;

    InProcessPaymentsClient(ObjectProvider<BookingPaymentsApi> api, ObjectProvider<CallerPrincipals> callers,
                            ListableBeanFactory beans) {
        InProcessCalls.requireRunning(beans.getBeanNamesForType(BookingPaymentsApi.class, true, false).length > 0,
                MODULE, "payments", "at.bookings.payments.mode");
        this.api = api;
        this.callers = callers;
    }

    @Override
    public Intent createIntent(CreateIntent r, String idempotencyKey) {
        return intent(call(() -> api.getObject().create(new BookingPaymentsApi.NewBookingPayment(r.orderId(),
                r.sellerOrg(), r.amountMinor(), r.currency(), r.lines().stream()
                .map(l -> new BookingPaymentsApi.Line(l.lineId(), l.amountMinor())).toList(), r.metadata(),
                r.placedAt(), r.shopperId(), r.saveForOffSession(), r.category()), idempotencyKey)));
    }

    @Override
    public Intent get(UUID paymentId, boolean refresh) {
        return intent(call(() -> api.getObject().get(paymentId, refresh)));
    }

    @Override
    public void capture(UUID paymentId, String captureId, List<String> lineIds, long amountMinor, boolean finalCapture,
                        String idempotencyKey) {
        call(() -> {
            api.getObject().capture(paymentId, captureId, lineIds, amountMinor, finalCapture, idempotencyKey);
            return null;
        });
    }

    @Override
    public void cancel(UUID paymentId, String reason, String idempotencyKey) {
        call(() -> {
            api.getObject().cancel(paymentId, reason == null ? "Booking cancelled" : truncate(reason), idempotencyKey);
            return null;
        });
    }

    @Override
    public Intent release(UUID paymentId, String reason, String idempotencyKey) {
        return intent(call(() -> api.getObject().release(paymentId, reason == null ? "Booking ended" : truncate(reason),
                idempotencyKey)));
    }

    @Override
    public Intent chargeOffSession(UUID paymentId, OffSessionCharge r, String idempotencyKey) {
        return intent(call(() -> api.getObject().chargeOffSession(paymentId, r.customerId(), r.paymentMethodId(),
                r.lineIds(), idempotencyKey)));
    }

    @Override
    public EffectiveFees effectiveFees(String category, String orgId) {
        BookingPaymentsApi.ConnectionFees f = call(() -> api.getObject().connectionFees(category, orgId));
        return new EffectiveFees(f.commissionBps(), f.commissionSource(), f.cardPctBps(), f.cardFixedMinor(), f.version(),
                f.currency(), f.kind());
    }

    @Override
    public void refund(UUID paymentId, String lineId, long amountMinor, String reason, String idempotencyKey) {
        call(() -> {
            api.getObject().refund(paymentId, lineId, amountMinor, reason == null ? "Booking cancelled"
                    : truncate(reason), idempotencyKey);
            return null;
        });
    }

    @Override
    public void serviceCompleted(String orderId, Instant completedAt) {
        call(() -> {
            api.getObject().serviceCompleted(orderId, completedAt);
            return null;
        });
    }

    private <T> T call(Supplier<T> call) {
        CallerPrincipal caller = callers.getObject().principalFor(MODULE);
        try {
            return InProcessCalls.as(caller, call);
        } catch (ModuleApiException e) {
            throw new PaymentsClientException(e.status(), e.code(), "Payments: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new PaymentsClientException(0, null, "Payments service unavailable: " + e.getMessage(), e);
        }
    }

    private static Intent intent(BookingPaymentsApi.BookingPayment p) {
        return new Intent(p.id(), p.orderId(), p.status(), p.clientSecret(), p.failureMessage(), p.releasedMinor(),
                p.parentPaymentId());
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
