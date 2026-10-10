package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.HeldOrder;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PayoutAsk;
import com.afrotransact.payments.domain.PayoutBalance;
import com.afrotransact.payments.domain.PayoutHold;
import com.afrotransact.payments.domain.PayoutPolicy;
import com.afrotransact.payments.domain.PayoutRequest;
import com.afrotransact.payments.domain.PayoutStatus;
import com.afrotransact.payments.domain.Requester;
import com.afrotransact.payments.domain.Settlement;
import com.afrotransact.payments.port.ConnectedAccountRepository;
import com.afrotransact.payments.port.IdempotencyStore;
import com.afrotransact.payments.port.LedgerRepository;
import com.afrotransact.payments.port.PayoutHoldRepository;
import com.afrotransact.payments.port.PayoutRepository;
import com.afrotransact.payments.port.ProviderFeeRepository;
import com.afrotransact.payments.port.SettlementRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class PayoutServiceImpl implements PayoutService {

    static final String PAYOUTS_PAUSED = "payments.payouts_paused";
    static final String RETAIL_NO_PAYOUTS = "payments.retail-no-payouts";

    private static final int MAX_LIST = 500;

    private final PayoutRepository payouts;
    private final SettlementRepository settlements;
    private final LedgerRepository ledger;
    private final ConnectedAccountRepository accounts;
    private final PayoutHoldRepository holds;
    private final ProviderFeeRepository providerFees;
    private final StripeGateway stripe;
    private final PayoutPolicy policy;
    private final IdempotencyStore idempotency;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final DomainEventPublisher events;
    private final PaymentsSettings settings;
    private final Clock clock;

    PayoutServiceImpl(PayoutRepository payouts, SettlementRepository settlements, LedgerRepository ledger,
                      ConnectedAccountRepository accounts, PayoutHoldRepository holds,
                      ProviderFeeRepository providerFees, StripeGateway stripe, PayoutPolicy policy,
                      IdempotencyStore idempotency, MoneyTransactions tx, PaymentsAccess access,
                      DomainEventPublisher events, PaymentsSettings settings, Clock clock) {
        this.payouts = payouts;
        this.settlements = settlements;
        this.ledger = ledger;
        this.accounts = accounts;
        this.holds = holds;
        this.providerFees = providerFees;
        this.stripe = stripe;
        this.policy = policy;
        this.idempotency = idempotency;
        this.tx = tx;
        this.access = access;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public PayoutBalance balance(String requestedOrg, String currency) {
        String org = access.orgOf(requestedOrg);
        access.balanceViewer(org);
        return tx.serializable(() -> compute(org, currency(currency)));
    }

    private PayoutBalance compute(String org, String currency) {
        Money zero = Money.zero(currency);
        if (isRetail(org)) {
            // AfroTransact Retail is the merchant of record: its sales are AfroTransact revenue (4400), nothing is
            // held, transferred or paid out, and compliance holds do not apply.
            return new PayoutBalance(org, currency, zero, List.of(), zero, zero, zero, zero, zero,
                    policy.minimum(currency), false, false, null);
        }
        Money transferred = settlements.transferredTo(org, currency);
        Map<PayoutStatus, Long> totals = payouts.totals(org, currency);
        long consumed = 0;
        long inFlight = 0;
        for (Map.Entry<PayoutStatus, Long> e : totals.entrySet()) {
            consumed += e.getKey().consumesBalance() ? e.getValue() : 0;
            inFlight += e.getKey().inFlight() ? e.getValue() : 0;
        }
        // Provider fees are debited from (or, while outstanding, reserved in) the connected account balance.
        Money fees = providerFees.totalOf(org, currency);
        // Label charges not yet recovered from a transfer (S256) are owed out of what was already transferred, so they
        // reserve it and may take available below zero; payouts never exceed available. Credits from voided labels
        // (negative) are paid with the next transfer, not from the connected account.
        Money labelCharges = Money.of(-ledger.unattributedPayable(org, currency), currency);
        Money available = transferred.minus(Money.of(consumed, currency)).minus(fees).max(zero)
                .minus(labelCharges.max(zero));
        List<HeldOrder> held = new ArrayList<>();
        Money heldTotal = zero;
        for (Map.Entry<String, Long> e : ledger.payableByOrder(org, currency).entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            Instant release = settlements.find(e.getKey()).map(Settlement::releaseAt).orElse(null);
            Money amount = Money.of(e.getValue(), currency);
            held.add(new HeldOrder(e.getKey(), amount, release));
            heldTotal = heldTotal.plus(amount);
        }
        held.sort(Comparator.comparing(HeldOrder::releaseAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(HeldOrder::orderId));
        List<PayoutRequest> today = payouts.requestsOn(org, today());
        Optional<PayoutHold> hold = holds.find(org).filter(PayoutHold::paused);
        return new PayoutBalance(org, currency, available, held, heldTotal, Money.of(inFlight, currency),
                Money.of(totals.getOrDefault(PayoutStatus.PAID, 0L), currency), fees, labelCharges,
                policy.minimum(currency),
                policy.canRequestToday(today), hold.isPresent(), hold.map(PayoutHold::reason).orElse(null));
    }

    @Override
    public PayoutRequest request(PayoutCommand cmd, String idempotencyKey) {
        String org = access.orgOf(cmd.orgId());
        Requester requester = access.payoutRequester(org);
        String currency = currency(cmd.currency());
        String fingerprint = Fingerprint.of(org + "|" + cmd);
        return tx.serializable(() -> {
            Optional<StoredRef> prior = idempotency.claim("payout-request", idempotencyKey, fingerprint,
                    StoredRef.class);
            if (prior.isPresent()) {
                return payouts.find(prior.get().id()).orElseThrow();
            }
            ensureNotRetail(org);
            ensureNotPaused(org);
            PayoutBalance balance = compute(org, currency);
            PayoutAsk ask = new PayoutAsk(cmd.amountMinor() == null ? null : Money.of(cmd.amountMinor(), currency),
                    cmd.all());
            Money amount = policy.validate(requester, ask, balance.available(), payouts.requestsOn(org, today()));
            if (accounts.find(org).isEmpty()) {
                throw new PaymentsRuleViolation("payments.no-connected-account", "Payouts are not set up for "
                        + "this organisation yet");
            }
            PayoutRequest request = PayoutRequest.request(UUID.randomUUID(), org, amount, requester.subject(),
                    Instant.now(clock), today());
            payouts.insert(request);
            publish(PaymentsEvents.PAYOUT_REQUESTED, request);
            idempotency.complete("payout-request", idempotencyKey, new StoredRef(request.id()));
            return request;
        });
    }

    @Override
    public List<PayoutRequest> queue(PayoutStatus status, String orgId, int limit) {
        access.approver();
        return payouts.list(Optional.ofNullable(status), Optional.ofNullable(orgId).filter(s -> !s.isBlank()),
                Math.max(1, Math.min(limit, MAX_LIST)));
    }

    @Override
    public PayoutRequest get(UUID id) {
        PayoutRequest r = payouts.find(id).orElseThrow(() -> notFound(id));
        access.balanceViewer(r.orgId());
        return r;
    }

    @Override
    public PayoutRequest approve(UUID id) {
        Actor actor = access.approver();
        return tx.serializable(() -> {
            PayoutRequest r = payouts.lock(id).orElseThrow(() -> notFound(id));
            if (r.status() == PayoutStatus.IN_TRANSIT || r.status() == PayoutStatus.PAID) {
                return r;
            }
            ensureNotRetail(r.orgId());
            ensureNotPaused(r.orgId());
            String account = accounts.find(r.orgId()).orElseThrow(() -> new PaymentsRuleViolation(
                    "payments.no-connected-account", "Organisation " + r.orgId() + " has no connected account"));
            r.approve(actor.subject(), Instant.now(clock), "pending");
            StripeGateway.PayoutSnapshot payout = stripe.createPayout(account, r.amount(),
                    Map.of("payout_request_id", r.id().toString(), "org_id", r.orgId()), "payout:" + r.id());
            PayoutRequest stored = payouts.update(r.approve(actor.subject(), Instant.now(clock), payout.id()));
            publish(PaymentsEvents.PAYOUT_APPROVED, stored);
            return stored;
        });
    }

    @Override
    public PayoutRequest hold(UUID id, String reason) {
        Actor actor = access.approver();
        if (reason == null || reason.isBlank()) {
            throw new PaymentsRuleViolation("payments.reason-required", "A hold needs a reason",
                    Map.of("reason", "Required"));
        }
        return tx.serializable(() -> payouts.update(payouts.lock(id).orElseThrow(() -> notFound(id))
                .hold(actor.subject(), Instant.now(clock), reason.trim())));
    }

    @Override
    public PayoutRequest release(UUID id) {
        Actor actor = access.approver();
        return tx.serializable(() -> payouts.update(payouts.lock(id).orElseThrow(() -> notFound(id))
                .release(actor.subject(), Instant.now(clock))));
    }

    private boolean isRetail(String org) {
        return settings.retailOrgId().equals(org);
    }

    private void ensureNotRetail(String org) {
        if (isRetail(org)) {
            throw new PaymentsRuleViolation(RETAIL_NO_PAYOUTS, "AfroTransact Retail is the merchant of record: its "
                    + "sales are AfroTransact revenue and are never paid out");
        }
    }

    /** Compliance holds from seller-service (seller.payouts.paused) block new requests and approvals. */
    private void ensureNotPaused(String org) {
        holds.find(org).filter(PayoutHold::paused).ifPresent(h -> {
            throw new PaymentsRuleViolation(PAYOUTS_PAUSED, "Payouts are paused for this organisation ("
                    + h.reason() + "). Resolve the account requirements in Seller Center; your balance keeps "
                    + "accruing.");
        });
    }

    private void publish(String type, PayoutRequest r) {
        events.publish(type, PaymentsEvents.AGGREGATE_PAYOUT, r.orgId(), PaymentsEventPayloads.PayoutPayload.of(r));
    }

    private LocalDate today() {
        return LocalDate.ofInstant(Instant.now(clock), settings.payoutZone());
    }

    private String currency(String requested) {
        return requested == null || requested.isBlank() ? settings.currency() : Money.zero(requested).currency();
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("payments.payout-not-found", "No payout request " + id);
    }
}
