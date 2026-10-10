package com.afrotransact.payments.domain;

import java.util.List;
import java.util.Optional;

/**
 * How one charge settles: per recipient and per line. Invariant (checked on construction):
 * {@code Σ seller nets + platform net + Σ tax = charge − Stripe fee} and {@code Σ Stripe-fee shares = Stripe fee}.
 */
public record PaymentSplit(Money charge, Money stripeFee, List<RecipientShare> recipients, List<LineSplit> lines) {

    public PaymentSplit {
        recipients = List.copyOf(recipients);
        lines = List.copyOf(lines);
        Money zero = Money.zero(charge.currency());
        Money nets = recipients.stream().map(RecipientShare::net).reduce(zero, Money::plus);
        Money tax = recipients.stream().map(RecipientShare::tax).reduce(zero, Money::plus);
        Money fees = recipients.stream().map(RecipientShare::stripeFee).reduce(zero, Money::plus);
        Money gross = recipients.stream().map(RecipientShare::gross).reduce(zero, Money::plus);
        if (!fees.equals(stripeFee) || !gross.equals(charge) || !nets.plus(tax).equals(charge.minus(stripeFee))) {
            throw new IllegalStateException("Split does not reconcile: charge " + charge + ", fee " + stripeFee
                    + ", nets " + nets + ", tax " + tax + ", fee shares " + fees + ", gross " + gross);
        }
    }

    public String currency() {
        return charge.currency();
    }

    public Money totalTax() {
        return recipients.stream().map(RecipientShare::tax).reduce(Money.zero(currency()), Money::plus);
    }

    public RecipientShare platform() {
        return recipients.stream().filter(RecipientShare::isPlatform).findFirst().orElseThrow();
    }

    public List<RecipientShare> sellers() {
        return recipients.stream().filter(r -> !r.isPlatform()).toList();
    }

    public Optional<LineSplit> line(String lineId) {
        return lines.stream().filter(l -> l.lineId().equals(lineId)).findFirst();
    }
}
