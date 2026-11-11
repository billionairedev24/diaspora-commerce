package com.afrotransact.orders.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.orders.domain.DeliveryQuote;
import com.afrotransact.orders.domain.OrdersRateLimited;
import com.afrotransact.orders.domain.OrdersRuleViolation;
import com.afrotransact.orders.domain.QuoteAction;
import com.afrotransact.orders.domain.QuoteContact;
import com.afrotransact.orders.domain.QuoteSettings;
import com.afrotransact.orders.domain.QuoteSnapshot;
import com.afrotransact.orders.domain.QuoteSnapshot.Line;
import com.afrotransact.orders.domain.QuoteSnapshot.Pack;
import com.afrotransact.orders.domain.QuoteSnapshot.Place;
import com.afrotransact.orders.domain.QuoteStatus;
import com.afrotransact.orders.domain.ShipTo;
import com.afrotransact.orders.port.AddressBook;
import com.afrotransact.orders.port.CartClient;
import com.afrotransact.orders.port.DeliveryQuoteRepository;
import com.afrotransact.orders.port.DeliveryQuoteRepository.QuoteEvent;
import com.afrotransact.orders.port.DeliveryQuoteSettingsRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

@Service
class DeliveryQuoteServiceImpl implements DeliveryQuoteService {

    static final int MAX_NOTE = 500;
    private static final int MY_LIMIT = 50;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrdersAccess access;
    private final DeliveryQuoteRepository quotes;
    private final DeliveryQuoteSettingsRepository settings;
    private final CartClient cart;
    private final AddressBook addresses;
    private final DeliveryQuoteDeskService desk;
    private final DomainEventPublisher events;
    private final TransactionOperations tx;
    private final Clock clock;

    DeliveryQuoteServiceImpl(OrdersAccess access, DeliveryQuoteRepository quotes,
                             DeliveryQuoteSettingsRepository settings, CartClient cart, AddressBook addresses,
                             DeliveryQuoteDeskService desk, DomainEventPublisher events, TransactionOperations tx,
                             Clock clock) {
        this.access = access;
        this.quotes = quotes;
        this.settings = settings;
        this.cart = cart;
        this.addresses = addresses;
        this.desk = desk;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public DeliveryQuoteView request(String customerBearer, String addressId, List<CheckoutCommand.SelectedLine> lines,
                                     QuoteContact contact, String note) {
        Actor customer = access.customer();
        if (addressId == null || addressId.isBlank()) {
            throw new OrdersRuleViolation("orders.address-required", "Choose a delivery address");
        }
        String text = note == null || note.isBlank() ? null : note.trim();
        if (text != null && text.length() > MAX_NOTE) {
            throw new OrdersRuleViolation("orders.quote-note-too-long", "Keep your note to " + MAX_NOTE
                    + " characters");
        }
        // The remote calls (cart, address book) happen before the transaction: no database connection waits on them.
        CartClient.Snapshot snapshot = cart.cart(customerBearer);
        List<CartClient.Item> items = CheckoutServiceImpl.select(snapshot, lines == null ? List.of() : lines);
        if (items.isEmpty()) {
            throw new OrdersRuleViolation(CheckoutCodes.CART_EMPTY, "Your cart is empty");
        }
        if (items.stream().anyMatch(CartClient.Item::unavailable)) {
            throw new OrdersRuleViolation(CheckoutCodes.CART_NOT_CHECKOUTABLE,
                    "Some items in your cart can no longer be bought. Remove them and try again.");
        }
        ShipTo to = addresses.find(customerBearer, addressId.trim())
                .orElseThrow(() -> new OrdersRuleViolation(CheckoutCodes.ADDRESS_NOT_FOUND,
                        "That address is not in your address book"));
        String currency = snapshot.currency() == null || snapshot.currency().isBlank() ? "USD" : snapshot.currency();
        QuoteSnapshot what = snapshot(items, to, currency, contact == null ? QuoteContact.EMAIL : contact, text);
        DeliveryQuote result = tx.execute(status -> create(customer.subject(), what));
        return DeliveryQuoteView.of(result, Instant.now(clock));
    }

    @Override
    public List<DeliveryQuoteView> mine() {
        Actor customer = access.customer();
        Instant now = Instant.now(clock);
        return quotes.forCustomer(customer.subject(), MY_LIMIT).stream().map(q -> DeliveryQuoteView.of(q, now))
                .toList();
    }

    @Override
    public DeliveryQuoteView get(String id) {
        Actor customer = access.customer();
        return DeliveryQuoteView.of(own(customer, id), Instant.now(clock));
    }

    private DeliveryQuote own(Actor customer, String id) {
        return parse(id).flatMap(quotes::findById)
                .filter(q -> q.customerId().equals(customer.subject()))
                .orElseThrow(() -> new NotFoundException("orders.quote-not-found", "Quote request not found"));
    }

    private static Optional<String> parse(String id) {
        try {
            return Optional.of(UUID.fromString(id).toString());
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    /** Inside one transaction: the same request when there is one, else a new one (rate limited per shopper). */
    private DeliveryQuote create(String customerId, QuoteSnapshot what) {
        Instant now = Instant.now(clock);
        QuoteSettings s = settings.load();
        String key = QuoteSnapshot.dedupeKey(what.cartHash(), what.shipToKey());
        Optional<DeliveryQuote> same = quotes.findLive(customerId, key);
        if (same.isEmpty()) {
            same = quotes.findRecent(customerId, key, now.minus(Duration.ofMinutes(s.duplicateWindowMinutes())));
        }
        if (same.isPresent()) {
            return same.get();
        }
        if (quotes.countSince(customerId, now.minus(Duration.ofHours(1))) >= s.requestsPerHour()) {
            throw new OrdersRateLimited("orders.quote-rate-limited", "You have asked for several quotes in the last "
                    + "hour. Please wait a little before asking again.", Duration.ofHours(1).toSeconds());
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            DeliveryQuote fresh = DeliveryQuote.requested(UUID.randomUUID().toString(), reference(now), customerId,
                    what, now);
            if (quotes.insert(fresh)) {
                quotes.appendEvent(new QuoteEvent(UUID.randomUUID().toString(), fresh.id(), QuoteAction.REQUESTED,
                        null, QuoteStatus.REQUESTED, customerId, what.customerName(), what.note(), null, now));
                events.publish(QuoteEvents.REQUESTED, OrdersEvents.AGGREGATE, fresh.id(),
                        new QuoteEvents.Lifecycle(fresh.id(), fresh.reference(), customerId, what.place().summary(),
                                what.lines().stream().mapToInt(Line::quantity).sum(), now));
                desk.handOut(fresh.id());
                return quotes.findById(fresh.id()).orElse(fresh);
            }
            Optional<DeliveryQuote> raced = quotes.findLive(customerId, key);
            if (raced.isPresent()) {
                return raced.get();
            }
            // The reference collided: try another.
        }
        throw new IllegalStateException("Could not allocate a quote reference");
    }

    private static String reference(Instant now) {
        StringBuilder sb = new StringBuilder("DQ-").append(now.atZone(ZoneOffset.UTC).getYear())
                .append('-');
        for (int i = 0; i < 6; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static QuoteSnapshot snapshot(List<CartClient.Item> items, ShipTo to, String currency,
                                          QuoteContact contact, String note) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Pack> packs = new LinkedHashMap<>();
        List<Line> lines = items.stream().map(i -> {
            String pack = packageId(i);
            counts.computeIfAbsent(pack, k -> new int[1])[0] += i.quantity();
            packs.putIfAbsent(pack, new Pack(pack, i.sellerName(), fulfilment(i), 0));
            return new Line(i.variantAtin(), i.title(), pack, i.quantity(), i.priceMinor());
        }).toList();
        List<Pack> packages = packs.values().stream()
                .map(p -> new Pack(p.packageId(), p.sellerName(), p.fulfilment(), counts.get(p.packageId())[0]))
                .toList();
        return new QuoteSnapshot(QuoteHashes.cart(items), QuoteSnapshot.hashAddress(to), currency, packages, lines,
                new Place(to.city(), to.state(), to.postalCode(), to.country() == null ? "US" : to.country()),
                to.fullName(), contact, note);
    }

    private static String packageId(CartClient.Item i) {
        return i.sellerOrgId() + ":" + fulfilment(i);
    }

    private static String fulfilment(CartClient.Item i) {
        return i.fulfilment() == null ? "SELLER" : i.fulfilment().toUpperCase(Locale.ROOT);
    }
}
