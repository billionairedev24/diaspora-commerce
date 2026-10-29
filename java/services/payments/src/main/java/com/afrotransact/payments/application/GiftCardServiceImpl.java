package com.afrotransact.payments.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.regex.Pattern;

import com.afrotransact.payments.domain.CreditEntry;
import com.afrotransact.payments.domain.CreditKind;
import com.afrotransact.payments.domain.CreditSource;
import com.afrotransact.payments.domain.FeeSchedule;
import com.afrotransact.payments.domain.GiftCard;
import com.afrotransact.payments.domain.GiftCardCodes;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.MoneySettings;
import com.afrotransact.payments.domain.PaymentsRateLimited;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.port.FeeScheduleRepository;
import com.afrotransact.payments.port.GiftCardAttemptRepository;
import com.afrotransact.payments.port.GiftCardRepository;
import com.afrotransact.payments.port.GiftCodeHasher;
import com.afrotransact.payments.port.IdempotencyStore;
import com.afrotransact.payments.port.MoneySettingsRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGateway.BalanceTxn;
import com.afrotransact.payments.port.StripeGateway.IntentSnapshot;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ForbiddenActionException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class GiftCardServiceImpl implements GiftCardService {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final int MAX_EMAIL = 254;
    private static final int MAX_NAME = 120;
    private static final int MAX_MESSAGE = 500;
    private static final Duration MAX_SEND_AHEAD = Duration.ofDays(366);
    private static final Duration PAST_GRACE = Duration.ofMinutes(10);
    private static final int MAX_LIST = 200;
    private static final int MIN_REASON = 15;
    private static final String CUSTOMER_SUBJECT = "customer:";
    private static final String ADDRESS_SUBJECT = "address:";

    private final GiftCardRepository cards;
    private final GiftCardAttemptRepository attempts;
    private final GiftCodeHasher hasher;
    private final CreditLedger credit;
    private final LedgerPoster poster;
    private final MoneyTransactions tx;
    private final StripeGateway stripe;
    private final IdempotencyStore idempotency;
    private final MoneySettingsRepository moneySettings;
    private final FeeScheduleRepository feeSchedules;
    private final DomainEventPublisher events;
    private final PaymentsAccess access;
    private final GiftCardSettings config;
    private final PaymentsSettings settings;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    GiftCardServiceImpl(GiftCardRepository cards, GiftCardAttemptRepository attempts, GiftCodeHasher hasher,
                        CreditLedger credit, LedgerPoster poster, MoneyTransactions tx, StripeGateway stripe,
                        IdempotencyStore idempotency, MoneySettingsRepository moneySettings,
                        FeeScheduleRepository feeSchedules, DomainEventPublisher events, PaymentsAccess access,
                        GiftCardSettings config, PaymentsSettings settings, Clock clock) {
        this.cards = cards;
        this.attempts = attempts;
        this.hasher = hasher;
        this.credit = credit;
        this.poster = poster;
        this.tx = tx;
        this.stripe = stripe;
        this.idempotency = idempotency;
        this.moneySettings = moneySettings;
        this.feeSchedules = feeSchedules;
        this.events = events;
        this.access = access;
        this.config = config;
        this.settings = settings;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ purchase

    @Override
    public Config config() {
        access.current();
        MoneySettings s = moneySettings.get();
        String currency = settings.currency();
        return new Config(Money.of(s.giftCardMinMinor(), currency), Money.of(s.giftCardMaxMinor(), currency),
                s.giftCardPresets(), s.giftCardTerms());
    }

    @Override
    public Purchase startPurchase(PurchaseCommand cmd, String idempotencyKey) {
        Actor customer = access.customer();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PaymentsRuleViolation("payments.idempotency-key-required",
                    "Buying a gift card needs an Idempotency-Key", Map.of("idempotencyKey", "Required"));
        }
        MoneySettings limits = moneySettings.get();
        Instant now = Instant.now(clock);
        Instant sendAt = cmd.sendAt() == null ? now : cmd.sendAt();
        validateCard(limits, cmd.amountMinor(), cmd.recipientEmail(), cmd.recipientName(), cmd.message(), sendAt,
                now);
        String fingerprint = Fingerprint.of(customer.subject() + "|" + cmd);
        return tx.serializable(() -> {
            Optional<StoredRef> prior = idempotency.claim("gift-card-purchase", idempotencyKey, fingerprint,
                    StoredRef.class);
            if (prior.isPresent()) {
                GiftCard existing = require(prior.get().id());
                return new Purchase(existing, existing.status() == GiftCard.Status.PENDING_PAYMENT
                        ? stripe.retrieveIntent(existing.paymentIntentId()).clientSecret() : null);
            }
            UUID id = Fingerprint.id("gift-card", customer.subject(), idempotencyKey);
            Money amount = Money.of(cmd.amountMinor(), settings.currency());
            Map<String, String> metadata = Map.of("kind", "gift_card", "gift_card_id", id.toString(),
                    "afrotransact_customer_id", customer.subject());
            IntentSnapshot intent = stripe.createIntent(new StripeGateway.CreateIntent(amount, null,
                    List.of("card"), "AfroTransact gift card " + id, metadata, null, false),
                    "giftcard:" + id + ":create-intent");
            GiftCard card = GiftCard.purchase(id, amount, customer.subject(), cmd.senderName(),
                    cmd.recipientEmail(), cmd.recipientName(), cmd.message(), cmd.locale(), sendAt, intent.id(), now);
            cards.insert(card);
            idempotency.complete("gift-card-purchase", idempotencyKey, new StoredRef(id));
            return new Purchase(card, intent.clientSecret());
        });
    }

    @Override
    public Purchase confirmPurchase(UUID id) {
        Actor customer = access.customer();
        return tx.serializable(() -> {
            GiftCard card = cards.lock(id).orElseThrow(() -> notFound(id));
            if (!customer.subject().equals(card.purchaserId())) {
                throw new ForbiddenActionException("payments.not-your-gift-card",
                        "This gift card was bought by another customer");
            }
            return new Purchase(settle(card), null);
        });
    }

    @Override
    public List<GiftCard> myPurchases() {
        Actor customer = access.customer();
        return cards.ofPurchaser(customer.subject(), MAX_LIST);
    }

    @Override
    public void onPaymentIntent(String paymentIntentId) {
        tx.serializable(() -> {
            cards.lockByIntent(paymentIntentId).ifPresent(this::settle);
            return null;
        });
    }

    /**
     * The purchase's PaymentIntent is read from Stripe: authorised is captured at once (a gift card is not shipped, so
     * there is nothing to wait for), then the card becomes active and its money a liability. Call with the row locked.
     */
    private GiftCard settle(GiftCard card) {
        if (card.status() != GiftCard.Status.PENDING_PAYMENT && card.status() != GiftCard.Status.PAYMENT_FAILED) {
            return card;
        }
        Instant now = Instant.now(clock);
        IntentSnapshot intent = stripe.retrieveIntent(card.paymentIntentId());
        String chargeId;
        switch (intent.status()) {
            case "requires_capture" -> chargeId = stripe.capture(card.paymentIntentId(), card.amount(), null,
                    Map.of("gift_card_id", card.id().toString()), "giftcard:" + card.id() + ":capture")
                    .latestChargeId();
            case "succeeded" -> chargeId = intent.latestChargeId();
            case "canceled" -> {
                return cards.update(card.paymentFailed(now));
            }
            default -> {
                return card;
            }
        }
        Money fee = cardFee(chargeId, card.amount(), now);
        poster.post(Postings.giftCardSold(Fingerprint.id("journal", "gift-card-sold", card.id().toString()), now,
                "gift-card-sold:" + card.id(), card, chargeId, fee), "gift-card:" + card.id());
        GiftCard paid = cards.update(card.paid(chargeId, now));
        publishAudit(PaymentsEvents.GIFT_CARD_PURCHASED, paid, card.purchaserId(), null);
        return paid.sendAt().isAfter(now) ? paid : deliver(paid);
    }

    private Money cardFee(String chargeId, Money amount, Instant now) {
        if (chargeId != null) {
            List<BalanceTxn> txns = stripe.chargeBalanceTransactions(chargeId);
            if (!txns.isEmpty()) {
                return Money.of(txns.stream().mapToLong(BalanceTxn::fee).sum(), amount.currency());
            }
        }
        return FeeSchedule.activeAt(feeSchedules.all(), now).map(s -> s.estimateCardFee(amount))
                .orElse(Money.zero(amount.currency()));
    }

    // ------------------------------------------------------------------ delivery

    @Override
    public int deliverDue() {
        return tx.serializable(() -> {
            List<GiftCard> due = cards.lockDue(Instant.now(clock), config.deliveryBatch());
            due.forEach(this::deliver);
            return due.size();
        });
    }

    @Override
    public int scrubDeliveredCodes() {
        return tx.serializable(cards::scrubPublishedDeliveryCodes);
    }

    /** Generates the code (a resend replaces the old one) and hands the email to notifications. */
    private GiftCard deliver(GiftCard card) {
        Instant now = Instant.now(clock);
        String code = GiftCardCodes.generate(random);
        String normalised = GiftCardCodes.normalise(code);
        GiftCard sent = cards.update(card.delivered(hasher.hash(normalised), GiftCardCodes.last4(normalised), now));
        events.publish(PaymentsEvents.GIFT_CARD_DELIVER, PaymentsEvents.AGGREGATE_PAYMENT, sent.id().toString(),
                new PaymentsEventPayloads.GiftCardDeliveryPayload(
                        new PaymentsEventPayloads.GiftCardRecipient(sent.recipientEmail()), sent.locale(),
                        sent.id().toString(), sent.recipientName(), sent.senderName(), sent.message(),
                        sent.amount().minor(), sent.amount().currency(), code));
        return sent;
    }

    // ------------------------------------------------------------------ redeem

    @Override
    public Redemption redeem(String code, String clientAddress) {
        Actor customer = access.customer();
        Instant now = Instant.now(clock);
        Instant since = now.minus(config.window());
        List<Long> reserved = new ArrayList<>();
        reserve(CUSTOMER_SUBJECT + customer.subject(), config.maxFailures(), since, now, reserved);
        if (clientAddress != null && !clientAddress.isBlank()) {
            reserve(ADDRESS_SUBJECT + clientAddress.trim(), config.maxFailuresAddress(), since, now, reserved);
        }
        String normalised = GiftCardCodes.normalise(code);
        if (normalised == null) {
            throw invalidCode();
        }
        String hash = hasher.hash(normalised);
        Redemption redemption = tx.serializable(() -> {
            GiftCard card = cards.lockByHash(hash).filter(c -> c.status() != GiftCard.Status.VOID)
                    .orElseThrow(GiftCardServiceImpl::invalidCode);
            if (card.status() == GiftCard.Status.REDEEMED) {
                throw new PaymentsStateConflict("payments.gift-card-redeemed", "This gift card was already redeemed");
            }
            Instant at = Instant.now(clock);
            GiftCard redeemed = cards.update(card.redeemed(customer.subject(), at));
            CreditEntry entry = credit.add(new CreditLedger.Add(customer.subject(), CreditKind.GRANT, card.amount(),
                    CreditSource.GIFT_CARD, "Gift card ending " + card.codeLast4() + " redeemed", customer.subject(),
                    null, card.id().toString(), "gift-card-redeem:" + card.id(), false));
            poster.post(Postings.giftCardRedeemed(Fingerprint.id("journal", "gift-card-redeemed",
                    card.id().toString()), at, "gift-card-redeemed:" + card.id(), card, customer.subject()),
                    customer.subject());
            publishAudit(PaymentsEvents.GIFT_CARD_REDEEMED, redeemed, customer.subject(), null);
            return new Redemption(redeemed, card.amount(), Money.of(entry.balanceAfterMinor(),
                    card.amount().currency()));
        });
        reserved.forEach(attempts::succeed);
        return redemption;
    }

    private void reserve(String subject, int max, Instant since, Instant now, List<Long> reserved) {
        OptionalLong id = attempts.reserve(subject, since, max, now);
        if (id.isPresent()) {
            reserved.add(id.getAsLong());
            return;
        }
        long retryAfter = attempts.oldestFailureSince(subject, since)
                .map(oldest -> Duration.between(now, oldest.plus(config.window())).toSeconds() + 1)
                .orElse(config.window().toSeconds());
        throw new PaymentsRateLimited("payments.gift-card-rate-limited",
                "Too many wrong codes; try again later", retryAfter);
    }

    private static NotFoundException invalidCode() {
        return new NotFoundException("payments.gift-card-invalid", "That gift card code is not valid");
    }

    // ------------------------------------------------------------------ staff

    @Override
    public GiftCard issue(IssueCommand cmd) {
        Actor staff = access.adjuster();
        MoneySettings limits = moneySettings.get();
        Instant now = Instant.now(clock);
        Instant sendAt = cmd.sendAt() == null ? now : cmd.sendAt();
        validateCard(limits, cmd.amountMinor(), cmd.recipientEmail(), cmd.recipientName(), cmd.message(), sendAt,
                now);
        return tx.serializable(() -> {
            GiftCard card = GiftCard.issuedByStaff(UUID.randomUUID(), Money.of(cmd.amountMinor(),
                    settings.currency()), staff.subject(), cmd.reason(), cmd.recipientEmail(), cmd.recipientName(),
                    cmd.message(), cmd.locale(), sendAt, now);
            cards.insert(card);
            poster.post(Postings.giftCardIssued(Fingerprint.id("journal", "gift-card-issued", card.id().toString()),
                    now, "gift-card-issued:" + card.id(), card), staff.subject());
            publishAudit(PaymentsEvents.GIFT_CARD_ISSUED, card, staff.subject(), card.issueReason());
            return sendAt.isAfter(now) ? card : deliver(card);
        });
    }

    @Override
    public GiftCard voidCard(UUID id, String reason) {
        Actor staff = access.adjuster();
        return tx.serializable(() -> {
            GiftCard card = cards.lock(id).orElseThrow(() -> notFound(id));
            if (card.status() == GiftCard.Status.VOID) {
                return card;
            }
            Instant now = Instant.now(clock);
            boolean refund = card.origin() == GiftCard.Origin.PURCHASE;
            GiftCard voided = card.voided(staff.subject(), reason, refund, now);
            String refundRef = null;
            if (refund) {
                refundRef = stripe.refund(card.paymentIntentId(), card.amount(),
                        Map.of("gift_card_id", id.toString()), "giftcard:" + id + ":refund").id();
            }
            poster.post(Postings.giftCardVoided(Fingerprint.id("journal", "gift-card-voided", id.toString()), now,
                    "gift-card-voided:" + id, card, refundRef), staff.subject());
            GiftCard stored = cards.update(voided);
            publishAudit(PaymentsEvents.GIFT_CARD_VOIDED, stored, staff.subject(), voided.voidReason());
            return stored;
        });
    }

    @Override
    public GiftCard resend(UUID id, String reason) {
        Actor staff = access.adjuster();
        if (reason == null || reason.trim().length() < MIN_REASON) {
            throw new PaymentsRuleViolation("payments.reason-required", "Say why (at least "
                    + MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        return tx.serializable(() -> {
            GiftCard card = cards.lock(id).orElseThrow(() -> notFound(id));
            if (card.status() != GiftCard.Status.ACTIVE) {
                throw new PaymentsStateConflict("payments.gift-card-state", "Only an active gift card can be sent "
                        + "again; this one is " + card.status());
            }
            GiftCard sent = deliver(card);
            publishAudit(PaymentsEvents.GIFT_CARD_RESENT, sent, staff.subject(), reason.trim());
            return sent;
        });
    }

    @Override
    public GiftCard get(UUID id) {
        access.reader();
        return require(id);
    }

    @Override
    public List<GiftCard> search(String query, GiftCard.Status status, int limit) {
        access.reader();
        return cards.search(query, status, Math.max(1, Math.min(limit, MAX_LIST)));
    }

    // ------------------------------------------------------------------ helpers

    private void validateCard(MoneySettings limits, long amountMinor, String email, String name, String message,
                              Instant sendAt, Instant now) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!limits.allowsGiftCard(amountMinor)) {
            errors.put("amountMinor", "Between " + Money.of(limits.giftCardMinMinor(), settings.currency()) + " and "
                    + Money.of(limits.giftCardMaxMinor(), settings.currency()));
        }
        if (email == null || email.length() > MAX_EMAIL || !EMAIL.matcher(email.trim()).matches()) {
            errors.put("recipientEmail", "A valid email address");
        }
        if (name != null && name.length() > MAX_NAME) {
            errors.put("recipientName", "At most " + MAX_NAME + " characters");
        }
        if (message != null && message.length() > MAX_MESSAGE) {
            errors.put("message", "At most " + MAX_MESSAGE + " characters");
        }
        if (sendAt.isBefore(now.minus(PAST_GRACE)) || sendAt.isAfter(now.plus(MAX_SEND_AHEAD))) {
            errors.put("sendAt", "From now to a year ahead");
        }
        if (!errors.isEmpty()) {
            throw new PaymentsRuleViolation("payments.gift-card-invalid-request", "Invalid gift card", errors);
        }
    }

    private void publishAudit(String type, GiftCard card, String actor, String reason) {
        events.publish(type, PaymentsEvents.AGGREGATE_PAYMENT, card.id().toString(),
                new PaymentsEventPayloads.GiftCardAuditPayload(card.id().toString(), type.substring(
                        type.lastIndexOf('.') + 1), actor, reason, card.amount().minor(), card.amount().currency(),
                        card.status().name()));
    }

    private GiftCard require(UUID id) {
        return cards.find(id).orElseThrow(() -> notFound(id));
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("payments.gift-card-not-found", "No gift card " + id);
    }
}
