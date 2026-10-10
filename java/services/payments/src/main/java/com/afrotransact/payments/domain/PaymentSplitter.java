package com.afrotransact.payments.domain;

import java.util.List;

/** Splits one charge between sellers, AfroTransact Retail and the platform (architecture §17b). */
public interface PaymentSplitter {

    /** AfroTransact's own recipient id in splits and on ledger lines. */
    String PLATFORM = "afrotransact";

    /**
     * @param lines     the lines covered by the charge (all in one currency, at least one)
     * @param schedule  fee schedule in force when the order was placed
     * @param stripeFee Stripe's fee for the charge (actual, or the schedule's estimate before capture)
     */
    PaymentSplit split(List<OrderLine> lines, FeeSchedule schedule, Money stripeFee);
}
