package com.afrotransact.bookings.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.MoneyMove;
import com.afrotransact.bookings.domain.Transition;
import com.afrotransact.bookings.port.PaymentsClient;
import org.springframework.stereotype.Component;

/**
 * Carries out a transition's money instructions against payments-service. Idempotency keys are derived from the
 * booking reference and the instruction, so a retried transition replays payments' first answer.
 */
@Component
class BookingMoney {

    private final PaymentsClient payments;
    private final BookingsSettings settings;

    BookingMoney(PaymentsClient payments, BookingsSettings settings) {
        this.payments = payments;
        this.settings = settings;
    }

    /**
     * Carries out the transition's instructions plus, right after a deposit capture of a long-dated booking, the
     * release of the balance hold (the card authorisation would lapse before the appointment).
     *
     * @return the transition with every instruction carried out
     */
    Transition apply(Booking b, Transition t, Instant now) {
        Transition all = t.plus(b.releaseBalanceAhead(settings.balance(), now));
        execute(b, all.moves(), now);
        return all;
    }

    void execute(Booking b, List<MoneyMove> moves, Instant now) {
        for (MoneyMove m : moves) {
            if (b.paymentId() == null) {
                continue;
            }
            String ref = b.reference();
            boolean offSessionBalance = MoneyMove.BALANCE.equals(m.lineId()) && b.balance().paymentId() != null;
            switch (m.kind()) {
                case CAPTURE -> {
                    if (offSessionBalance) {
                        payments.capture(b.balance().paymentId(), ref + "-balance-os", List.of(m.lineId()),
                                m.amountMinor(), true, ref + ":offsession:" + b.balance().attempts() + ":capture");
                    } else {
                        payments.capture(b.paymentId(), ref + "-" + m.lineId(), List.of(m.lineId()),
                                m.amountMinor(), m.finalCapture(), ref + ":capture:" + m.lineId());
                    }
                }
                case RELEASE -> {
                    if (offSessionBalance) {
                        payments.cancel(b.balance().paymentId(), reason(b),
                                ref + ":offsession:" + b.balance().attempts() + ":cancel");
                    } else {
                        payments.cancel(b.paymentId(), reason(b), ref + ":cancel");
                    }
                }
                case RELEASE_REMAINING -> payments.release(b.paymentId(), reason(b), ref + ":release");
                case REFUND -> payments.refund(b.paymentId(), m.lineId(), m.amountMinor(), reason(b),
                        ref + ":refund:" + m.lineId());
                case SETTLE -> payments.serviceCompleted(ref, now);
                default -> {
                    // AUTHORIZE and PROVIDER_FEE are bookkeeping only
                }
            }
        }
    }

    private static String reason(Booking b) {
        if (b.reason() != null) {
            return b.reason();
        }
        return b.status().isActive() ? "Appointment beyond the card authorisation window" : b.status().label();
    }
}
