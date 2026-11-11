package com.afrotransact.orders.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.orders.domain.DeliveryQuote;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.QuoteAction;
import com.afrotransact.orders.domain.QuoteSnapshot;
import com.afrotransact.orders.domain.QuoteStatus;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.port.CartClient;
import com.afrotransact.orders.port.DeliveryQuoteRepository;
import com.afrotransact.orders.port.DeliveryQuoteRepository.QuoteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DeliveryQuoteRedemptionServiceImpl implements DeliveryQuoteRedemptionService {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryQuoteRedemptionServiceImpl.class);
    static final String NOT_FOUND = "orders.quote-not-found";
    static final String CHANGED = "orders.quote-changed";
    static final String EXPIRED = "orders.quote-expired";
    static final String USED = "orders.quote-used";
    static final String NOT_READY = "orders.quote-not-ready";

    private final DeliveryQuoteRepository quotes;
    private final Clock clock;

    DeliveryQuoteRedemptionServiceImpl(DeliveryQuoteRepository quotes, Clock clock) {
        this.quotes = quotes;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Redeemed redeem(String customerId, String quoteId, List<CartClient.Item> items, ShipTo shipTo,
                           String orderId) {
        Instant now = Instant.now(clock);
        String cart = QuoteHashes.cart(items);
        String address = QuoteSnapshot.hashAddress(shipTo);
        Optional<DeliveryQuote> id = parse(quoteId).flatMap(quotes::findById);
        // Someone else's quote is reported exactly like a missing one.
        if (id.isEmpty() || !id.get().customerId().equals(customerId)) {
            throw new OrdersRuleViolation(NOT_FOUND, "We can't find that delivery quote. Ask for a new one.");
        }
        Optional<DeliveryQuote> used = quotes.consume(quoteId, customerId, cart, address, orderId, now);
        if (used.isPresent()) {
            DeliveryQuote q = used.get();
            if (id.get().status() == QuoteStatus.READY) { // this call consumed it (a retry finds it used already)
                quotes.appendEvent(new QuoteEvent(UUID.randomUUID().toString(), q.id(), QuoteAction.USED,
                        QuoteStatus.READY, QuoteStatus.USED, customerId, q.snapshot().customerName(),
                        "Used by an order", q.answer().amountMinor(), now));
                LOG.info("Delivery quote {} used by order {}", q.reference(), orderId);
            }
            return new Redeemed(q.id(), q.reference(), q.answer().amountMinor(), q.snapshot().currency());
        }
        throw refusal(quotes.findById(quoteId).orElseThrow(), cart, address, now);
    }

    @Override
    @Transactional
    public void restore(String orderId) {
        Optional<DeliveryQuote> found = parse(orderId).flatMap(quotes::findByOrder);
        if (found.isEmpty()) {
            return;
        }
        quotes.lockById(found.get().id()).filter(q -> q.status() == QuoteStatus.USED && orderId.equals(q.usedOrderId()))
                .ifPresent(q -> {
                    Instant now = Instant.now(clock);
                    DeliveryQuote next = quotes.save(q.restore(now));
                    quotes.appendEvent(new QuoteEvent(UUID.randomUUID().toString(), q.id(), QuoteAction.RESTORED,
                            QuoteStatus.USED, next.status(), DeliveryQuoteDeskServiceImpl.SYSTEM, "System",
                            "The order did not complete; the quote can be used again until it expires",
                            q.answer().amountMinor(), now));
                    LOG.info("Delivery quote {} restored after order {} did not complete", q.reference(), orderId);
                });
    }

    private static OrdersRuleViolation refusal(DeliveryQuote q, String cart, String address, Instant now) {
        if (q.status() == QuoteStatus.USED) {
            return new OrdersRuleViolation(USED, "This delivery quote has already been used. Ask for a new one.");
        }
        if (q.status() == QuoteStatus.EXPIRED || q.status() == QuoteStatus.READY && !q.usable(now)) {
            return new OrdersRuleViolation(EXPIRED, "This delivery quote has expired. Ask for a new one.");
        }
        if (q.status() != QuoteStatus.READY) {
            return new OrdersRuleViolation(NOT_READY, "This delivery quote isn't ready yet. We'll let you know when "
                    + "it is.");
        }
        if (!q.snapshot().cartHash().equals(cart) || !q.snapshot().shipToKey().equals(address)) {
            return new OrdersRuleViolation(CHANGED, "Your cart or delivery address changed since this quote was "
                    + "agreed, so it no longer applies. Ask for a new quote.");
        }
        return new OrdersRuleViolation(NOT_FOUND, "We can't use that delivery quote. Ask for a new one.");
    }

    private static Optional<String> parse(String id) {
        try {
            return Optional.of(UUID.fromString(id).toString());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }
}
