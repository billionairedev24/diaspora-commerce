package com.afrotransact.payments.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What one capture request actually captures, given how the PaymentIntent was created.
 *
 * <ul>
 *   <li>Multicapture intent: the requested lines; Stripe keeps the rest capturable unless this capture is final.</li>
 *   <li>Single-capture intent (the account has no Stripe multicapture): the first capture is the only one, Stripe
 *       releases whatever it does not take. An order whose captures follow its shipments
 *       ({@link Payment#capturedPerShipment()}) therefore captures every line not captured yet with its first shipment,
 *       so nothing is lost; other payments (a booking deposit) capture what they asked for and the rest is released,
 *       to be charged off-session later.</li>
 * </ul>
 *
 * @param lines              the lines to capture, the requested ones first
 * @param finalCapture       no further capture of this payment is possible afterwards
 * @param stripeFinalCapture the {@code final_capture} parameter to send; null sends none (single-capture intents)
 */
public record CapturePlan(List<PaymentLine> lines, boolean finalCapture, Boolean stripeFinalCapture) {

    public CapturePlan {
        lines = List.copyOf(lines);
    }

    /**
     * @param payment        the payment, capturable
     * @param requested      the lines the caller asked for (not captured yet, at least one)
     * @param requestedFinal the caller asked to release the rest after this capture
     */
    public static CapturePlan of(Payment payment, List<PaymentLine> requested, boolean requestedFinal) {
        Money asked = total(requested, payment.currency());
        if (payment.multicapture()) {
            boolean last = requestedFinal || payment.captured().plus(asked).equals(payment.amount());
            return new CapturePlan(requested, last, last);
        }
        if (!payment.capturedPerShipment()) {
            return new CapturePlan(requested, true, null);
        }
        Set<String> chosen = new LinkedHashSet<>();
        List<PaymentLine> lines = new ArrayList<>(requested);
        requested.forEach(l -> chosen.add(l.lineId()));
        for (PaymentLine l : payment.lines()) {
            if (!l.isCaptured() && chosen.add(l.lineId())) {
                lines.add(l);
            }
        }
        return new CapturePlan(lines, true, null);
    }

    /** The amount captured: Σ items + shipping + tax of the lines. */
    public Money amount(String currency) {
        return total(lines, currency);
    }

    /** True when lines of other shipments are captured with the requested ones (single-capture order). */
    public boolean widened(List<PaymentLine> requested) {
        return lines.size() > requested.size();
    }

    private static Money total(List<PaymentLine> lines, String currency) {
        Money sum = Money.zero(currency);
        for (PaymentLine l : lines) {
            sum = sum.plus(l.line().total());
        }
        return sum;
    }
}
