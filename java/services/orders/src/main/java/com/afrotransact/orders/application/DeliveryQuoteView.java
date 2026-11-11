package com.afrotransact.orders.application;

import java.time.Instant;
import java.util.List;

import com.afrotransact.orders.domain.DeliveryQuote;
import com.afrotransact.orders.domain.QuoteSnapshot;
import com.afrotransact.orders.domain.QuoteStatus;

/**
 * A shopper's view of their quote request. The amount shows once it is agreed; internal reasons and staff names never
 * do. {@code message} says in plain words where it stands and what to do next.
 *
 * @param status       REQUESTED, IN_PROGRESS, READY, EXPIRED, DECLINED or USED
 * @param statusLabel  Requested, Being quoted, Quote ready, Expired, Declined or Used
 * @param amountMinor  the agreed delivery charge; null until the quote is ready
 * @param expiresAt    when a ready quote stops being usable
 * @param usable       the quote can be applied at checkout right now
 */
public record DeliveryQuoteView(String id, String reference, String status, String statusLabel, Long amountMinor,
                                String currency, Instant expiresAt, boolean usable, String place,
                                List<QuoteSnapshot.QuotePack> packages, List<QuoteSnapshot.QuoteLine> lines, String contact,
                                String note, Instant createdAt, Instant updatedAt, String message) {

    static DeliveryQuoteView of(DeliveryQuote q, Instant now) {
        boolean usable = q.usable(now);
        // A ready quote past its validity reads as expired at once; the sweep records it a moment later.
        QuoteStatus status = q.status() == QuoteStatus.READY && !usable ? QuoteStatus.EXPIRED : q.status();
        Long amount = q.answer() == null ? null : q.answer().amountMinor();
        Instant expires = q.answer() == null ? null : q.answer().expiresAt();
        return new DeliveryQuoteView(q.id(), q.reference(), status.name(), status.label(),
                amount,
                q.snapshot().currency(), expires, usable, q.snapshot().place().summary(), q.snapshot().packages(),
                q.snapshot().lines(), q.snapshot().contact().name(), q.snapshot().note(), q.createdAt(),
                q.updatedAt(), message(status, usable));
    }

    private static String message(QuoteStatus status, boolean usable) {
        return switch (status) {
            case REQUESTED -> "We have your request. A member of our team will price the delivery and let you know.";
            case IN_PROGRESS -> "Our team is working out the delivery price for your address.";
            case READY -> usable ? "Your delivery quote is ready. Go back to checkout to use it before it expires."
                    : "This quote has expired. Ask for a new one.";
            case DECLINED -> "We can't offer delivery to this address right now. You can choose pickup instead.";
            case EXPIRED -> "This quote expired before it was used. Ask for a new one.";
            case USED -> "This quote was used for an order.";
        };
    }
}
