package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.AccountBalance;
import com.afrotransact.payments.domain.AdjustmentStatus;
import com.afrotransact.payments.domain.Chart;
import com.afrotransact.payments.domain.CreditAdjustment;
import com.afrotransact.payments.domain.CreditEntry;
import com.afrotransact.payments.domain.CreditKind;
import com.afrotransact.payments.domain.GiftCard;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.MoneySettings;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.port.CreditAdjustmentRepository;
import com.afrotransact.payments.port.CreditLedgerRepository;
import com.afrotransact.payments.port.GiftCardRepository;
import com.afrotransact.payments.port.LedgerRepository;
import com.afrotransact.payments.port.MoneySettingsRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class CreditServiceImpl implements CreditService {

    private static final int MAX_LIST = 500;
    /** No single staff or program grant above 10,000.00: a sanity bound, not the approval limit. */
    private static final long MAX_GRANT_MINOR = 1_000_000L;

    private final CreditLedger ledger;
    private final CreditLedgerRepository entries;
    private final CreditAdjustmentRepository adjustments;
    private final MoneySettingsRepository moneySettings;
    private final GiftCardRepository giftCards;
    private final LedgerRepository generalLedger;
    private final MoneyTransactions tx;
    private final DomainEventPublisher events;
    private final PaymentsAccess access;
    private final PaymentsSettings settings;
    private final Clock clock;

    CreditServiceImpl(CreditLedger ledger, CreditLedgerRepository entries, CreditAdjustmentRepository adjustments,
                      MoneySettingsRepository moneySettings, GiftCardRepository giftCards,
                      LedgerRepository generalLedger, MoneyTransactions tx, DomainEventPublisher events,
                      PaymentsAccess access, PaymentsSettings settings, Clock clock) {
        this.ledger = ledger;
        this.entries = entries;
        this.adjustments = adjustments;
        this.moneySettings = moneySettings;
        this.giftCards = giftCards;
        this.generalLedger = generalLedger;
        this.tx = tx;
        this.events = events;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public Balance myBalance() {
        String customer = access.customer().subject();
        return balance(customer);
    }

    @Override
    public List<CreditEntry> myEntries(Instant before, int limit) {
        String customer = access.customer().subject();
        return entries.entries(customer, before, clamp(limit));
    }

    @Override
    public Balance balanceOf(String customerId) {
        access.creditReader();
        return balance(requireCustomer(customerId));
    }

    @Override
    public CustomerCredit customer(String customerId, int limit) {
        access.reader();
        String customer = requireCustomer(customerId);
        return new CustomerCredit(balance(customer), entries.entries(customer, null, clamp(limit)),
                adjustments.list(null, customer, 50));
    }

    @Override
    public CreditEntry grant(GrantCommand c) {
        Actor actor = access.creditGranter();
        if (c.idempotencyKey() == null || c.idempotencyKey().isBlank()) {
            throw new PaymentsRuleViolation("payments.idempotency-key-required", "Credit grants need an "
                    + "Idempotency-Key", Map.of("idempotencyKey", "Required"));
        }
        if (c.source() == null || !c.source().grantableByService()) {
            throw new PaymentsRuleViolation("payments.credit-source", "Programs grant credit as REFERRAL, "
                    + "MEMBERSHIP or PROMO", Map.of("source", "REFERRAL, MEMBERSHIP or PROMO"));
        }
        requireGrantable(c.amount());
        requireReason(c.reason());
        return ledger.add(new CreditLedger.Add(requireCustomer(c.customerId()), CreditKind.GRANT, c.amount(),
                c.source(), c.reason().trim(), actor.subject(), c.orderId(), c.reference(),
                "grant:" + c.idempotencyKey(), true));
    }

    @Override
    public CreditAdjustment adjust(AdjustCommand cmd) {
        Actor actor = access.adjuster();
        requireGrantable(cmd.amount().abs());
        MoneySettings limits = moneySettings.get();
        return tx.serializable(() -> {
            Instant now = Instant.now(clock);
            CreditAdjustment proposed = CreditAdjustment.propose(UUID.randomUUID(), cmd.customerId(), cmd.amount(),
                    cmd.source(), cmd.reason(), actor.subject(), now);
            adjustments.insert(proposed);
            if (limits.needsApproval(cmd.amount().abs().minor())) {
                return proposed;
            }
            CreditEntry entry = post(proposed, actor.subject());
            return published(adjustments.update(proposed.postedByMaker(entry.id(), now)));
        });
    }

    @Override
    public CreditAdjustment approve(UUID id, String note) {
        Actor actor = access.adjustmentApprover();
        return tx.serializable(() -> {
            CreditAdjustment current = adjustments.lock(id).orElseThrow(() -> notFound(id));
            Instant now = Instant.now(clock);
            // the four-eyes rule is checked before anything is posted
            current.approve(actor.subject(), note, UUID.randomUUID(), now);
            CreditEntry entry = post(current, actor.subject());
            return published(adjustments.update(current.approve(actor.subject(), note, entry.id(), now)));
        });
    }

    @Override
    public CreditAdjustment reject(UUID id, String note) {
        Actor actor = access.adjustmentApprover();
        return tx.serializable(() -> {
            CreditAdjustment current = adjustments.lock(id).orElseThrow(() -> notFound(id));
            return adjustments.update(current.reject(actor.subject(), note, Instant.now(clock)));
        });
    }

    @Override
    public List<CreditAdjustment> adjustments(AdjustmentStatus status, String customerId, int limit) {
        access.reader();
        return adjustments.list(status, customerId == null || customerId.isBlank() ? null : customerId.trim(),
                clamp(limit));
    }

    @Override
    public Liabilities liabilities() {
        access.reader();
        CreditLedgerRepository.Totals totals = entries.totals();
        Map<GiftCard.Status, long[]> cards = giftCards.totalsByStatus();
        long ledgerCredit = ledgerBalance(Chart.CUSTOMER_CREDIT);
        long ledgerGiftCards = ledgerBalance(Chart.GIFT_CARD_LIABILITY);
        return new Liabilities(settings.currency(), totals.outstandingMinor(), totals.accountsWithBalance(),
                totals.flowsMinor(), cards.get(GiftCard.Status.ACTIVE)[1], cards.get(GiftCard.Status.ACTIVE)[0],
                cards.get(GiftCard.Status.REDEEMED)[1], cards.get(GiftCard.Status.VOID)[1], ledgerCredit,
                ledgerGiftCards, totals.outstandingMinor() - ledgerCredit);
    }

    /** The audit event of a posted adjustment (key = customer id). */
    private CreditAdjustment published(CreditAdjustment posted) {
        events.publish(PaymentsEvents.CREDIT_ADJUSTED, PaymentsEvents.AGGREGATE_PAYMENT, posted.customerId(),
                new PaymentsEventPayloads.CreditAdjustedPayload(posted.id().toString(), posted.customerId(),
                        posted.amount().minor(), posted.amount().currency(), posted.source().name(), posted.reason(),
                        posted.proposedBy(), posted.decidedBy()));
        return posted;
    }

    private long ledgerBalance(Chart chart) {
        return generalLedger.balances(Optional.empty()).stream()
                .filter(b -> b.account().chart() == chart && b.currency().equals(settings.currency()))
                .mapToLong(AccountBalance::balance).sum();
    }

    /** Appends the entry of an adjustment: credit added is a grant, credit taken back an adjustment. */
    private CreditEntry post(CreditAdjustment a, String actor) {
        String key = "adjustment:" + a.id();
        if (a.amount().isPositive()) {
            return ledger.add(new CreditLedger.Add(a.customerId(), CreditKind.GRANT, a.amount(), a.source(),
                    a.reason(), actor, null, a.id().toString(), key, true));
        }
        return ledger.take(new CreditLedger.Take(a.customerId(), a.amount().negate(), a.reason(), actor,
                a.id().toString(), key));
    }

    private Balance balance(String customerId) {
        return new Balance(customerId, Money.of(entries.balance(customerId), settings.currency()));
    }

    private static void requireGrantable(Money amount) {
        if (amount == null || amount.isZero() || amount.abs().minor() > MAX_GRANT_MINOR) {
            throw new PaymentsRuleViolation("payments.credit-amount", "Credit amounts are above zero and at most "
                    + MAX_GRANT_MINOR / 100 + ".00", Map.of("amountMinor", "Out of range"));
        }
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new PaymentsRuleViolation("payments.reason-required", "Say why", Map.of("reason", "Required"));
        }
    }

    private static String requireCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Name the customer",
                    Map.of("customerId", "Required"));
        }
        return customerId.trim();
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIST));
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("payments.credit-adjustment-not-found", "No credit adjustment " + id);
    }
}
