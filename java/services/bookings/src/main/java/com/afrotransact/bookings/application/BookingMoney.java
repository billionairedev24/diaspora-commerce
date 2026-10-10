package com.afrotransact.bookings.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.MoneyMove;
import com.afrotransact.bookings.port.PaymentsClient;
import org.springframework.stereotype.Component;

/**
 * Carries out a transition's money instructions against payments-service. Idempotency keys are derived from the
 * booking reference and the instruction, so a retried transition replays payments' first answer.
 */
@Component
class BookingMoney {

    private final PaymentsClient payments;

    BookingMoney(PaymentsClient payments) {
        this.payments = payments;
    }

    void execute(Booking b, List<MoneyMove> moves, Instant now) {
        for (MoneyMove m : moves) {
            if (b.paymentId() == null) {
                continue;
            }
            String ref = b.reference();
            switch (m.kind()) {
                case CAPTURE -> payments.capture(b.paymentId(), ref + "-" + m.lineId(), List.of(m.lineId()),
                        m.amountMinor(), m.finalCapture(), ref + ":capture:" + m.lineId());
                case RELEASE -> payments.cancel(b.paymentId(), b.reason() == null ? b.status().label() : b.reason(),
                        ref + ":cancel");
                case REFUND -> payments.refund(b.paymentId(), m.lineId(), m.amountMinor(),
                        b.reason() == null ? b.status().label() : b.reason(), ref + ":refund:" + m.lineId());
                case SETTLE -> payments.serviceCompleted(ref, now);
                default -> {
                    // AUTHORIZE and PROVIDER_FEE are bookkeeping only
                }
            }
        }
    }
}
