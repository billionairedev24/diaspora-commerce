package com.afrotransact.orders.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.orders.domain.DeliveryQuote;
import com.afrotransact.orders.domain.QuoteAction;
import com.afrotransact.orders.domain.QuoteStatus;

/** Delivery quote requests, their timeline and the single-use consumption at checkout. */
public interface DeliveryQuoteRepository {

    /** Inserts a new request; false when the shopper already has a live one for the same cart and address. */
    boolean insert(DeliveryQuote quote);

    /** Stores the new state; the row's version must still be {@code quote.version()} (else the write is refused). */
    DeliveryQuote save(DeliveryQuote quote);

    Optional<DeliveryQuote> findById(String id);

    /** Reads the row under a write lock (held to the end of the transaction). */
    Optional<DeliveryQuote> lockById(String id);

    /** The shopper's live request (requested, being quoted or ready) for this cart and address. */
    Optional<DeliveryQuote> findLive(String customerId, String dedupeKey);

    /** The shopper's newest request for this cart and address created at or after {@code since}, whatever its state. */
    Optional<DeliveryQuote> findRecent(String customerId, String dedupeKey, Instant since);

    List<DeliveryQuote> forCustomer(String customerId, int limit);

    /** How many requests the shopper started since {@code since} (rate limit). */
    int countSince(String customerId, Instant since);

    Page<DeliveryQuote> search(Search search);

    /** Requests awaiting staff, oldest first (escalation checks). */
    List<String> escalationCandidates(int limit);

    /** Ready quotes whose validity ended before {@code now}. */
    List<String> expiredBefore(Instant now, int limit);

    /** Open (awaiting staff) cases per assignee, for least-loaded hand-out. */
    Map<String, Integer> openLoad();

    /**
     * Consumes a ready quote for {@code orderId} in one statement: only when it belongs to the shopper, matches the
     * cart and address hashes and has not expired or been used. Repeating it for the same order returns the used
     * quote (so a retried saga step is safe). Empty when none of that holds; {@link #findById} says why.
     */
    Optional<DeliveryQuote> consume(String quoteId, String customerId, String cartHash, String shipToKey,
                                    String orderId, Instant now);

    /** The quote used by an order, if any. */
    Optional<DeliveryQuote> findByOrder(String orderId);

    void appendEvent(QuoteEvent event);

    List<QuoteEvent> events(String quoteId);

    /** One timeline / audit row. */
    record QuoteEvent(String id, String quoteId, QuoteAction action, QuoteStatus from, QuoteStatus to, String actorId,
                      String actorName, String note, Long amountMinor, Instant at) {
    }

    /**
     * A staff queue query. {@code queue} is a tab; {@code assigneeId} and {@code viewerId} are used by MY.
     *
     * @param escalatedOnly only cases escalated and still awaiting staff
     */
    record Search(Queue queue, String viewerId, String assigneeId, boolean unassignedOnly, boolean escalatedOnly,
                  String text, int offset, int limit) {

        public enum Queue {
            MY,
            UNASSIGNED,
            WAITING,
            CLOSED,
            ALL
        }
    }
}
