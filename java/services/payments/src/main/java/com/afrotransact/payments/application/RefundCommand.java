package com.afrotransact.payments.application;

import java.util.List;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.RefundFault;

/**
 * Line-level partial refund.
 *
 * @param fault        who caused it (AfroTransact Delivery lines, S255); null = SELLER
 * @param cardAsCredit the shopper (or staff, with a reason) chose credit for the part that was paid with the card
 *                     (S320). One way: credit is never turned back into a card refund or cash
 * @param chosenBy     who chose it (the customer's id or the staff member), kept on the credit entry
 */
public record RefundCommand(List<Line> lines, String reason, RefundFault fault, boolean cardAsCredit,
                            String chosenBy) {

    public RefundCommand {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** A refund that goes back to what paid it, with the fault named. */
    public RefundCommand(List<Line> lines, String reason, RefundFault fault) {
        this(lines, reason, fault, false, null);
    }

    /** A seller-fault refund. */
    public RefundCommand(List<Line> lines, String reason) {
        this(lines, reason, null);
    }

    public RefundFault faultOrDefault() {
        return fault == null ? RefundFault.SELLER : fault;
    }

    /** Stable text for idempotency fingerprints (unchanged for refunds that name no fault). */
    @Override
    public String toString() {
        return "RefundCommand[lines=" + lines + ", reason=" + reason + (fault == null ? "" : ", fault=" + fault)
                + (cardAsCredit ? ", cardAsCredit=true, chosenBy=" + chosenBy : "") + "]";
    }

    /** Amounts of one line to give back. */
    public record Line(String lineId, Money items, Money shipping, Money tax) {
    }
}
