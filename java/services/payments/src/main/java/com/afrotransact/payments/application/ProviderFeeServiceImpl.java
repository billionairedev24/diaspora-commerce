package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.domain.ProviderFee;
import com.afrotransact.payments.port.ConnectedAccountRepository;
import com.afrotransact.payments.port.ProviderFeeRepository;
import com.afrotransact.payments.port.StripeGateway;
import com.afrotransact.payments.port.StripeGatewayException;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.events.InboxDeduplicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Connect mechanism: an <b>account debit</b> (a Charge with {@code source = acct_…}) — not a transfer reversal. The
 * fee is not tied to any transfer: AfroTransact sends providers' nets as separate transfers per order, and the
 * cancelled booking usually has nothing transferred at all (its deposit was refunded in full). A reversal would have
 * to pick an unrelated order's transfer, would book the money back against that order's payable and is capped by
 * what is left of that one transfer. An account debit takes the amount from the connected account's balance as a
 * platform balance transaction of its own that reconciliation matches 1:1 with the collection journal.
 */
@Service
class ProviderFeeServiceImpl implements ProviderFeeService {

    static final String CONSUMER = "payments.bookings-events";

    private static final Logger LOG = LoggerFactory.getLogger(ProviderFeeServiceImpl.class);
    private static final String SYSTEM = "system:provider-fees";
    private static final int MAX_LIST = 200;

    private final ProviderFeeRepository fees;
    private final ConnectedAccountRepository accounts;
    private final StripeGateway stripe;
    private final LedgerPoster ledger;
    private final InboxDeduplicator inbox;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final DomainEventPublisher events;
    private final PaymentsSettings settings;
    private final Clock clock;

    ProviderFeeServiceImpl(ProviderFeeRepository fees, ConnectedAccountRepository accounts, StripeGateway stripe,
                           LedgerPoster ledger, InboxDeduplicator inbox, MoneyTransactions tx, PaymentsAccess access,
                           DomainEventPublisher events, PaymentsSettings settings, Clock clock) {
        this.fees = fees;
        this.accounts = accounts;
        this.stripe = stripe;
        this.ledger = ledger;
        this.inbox = inbox;
        this.tx = tx;
        this.access = access;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public boolean lateCancellation(ProviderFeeNotice n) {
        if (n.orgId() == null || n.orgId().isBlank() || n.bookingId() == null || n.bookingId().isBlank()
                || n.amountMinor() <= 0 || n.currency() == null || n.currency().isBlank()) {
            LOG.warn("Ignoring provider fee event {}: incomplete ({})", n.eventId(), n);
            return false;
        }
        if (n.orgId().trim().equals(settings.retailOrgId())) {
            LOG.info("Ignoring provider fee event {}: AfroTransact Retail is not charged seller fees", n.eventId());
            return false;
        }
        return tx.serializable(() -> {
            if (!inbox.firstDelivery(CONSUMER, n.eventId())) {
                return false;
            }
            Instant now = Instant.now(clock);
            ProviderFee fee = ProviderFee.record(Fingerprint.id("provider-fee", n.bookingId()), n.bookingId(),
                    n.bookingReference(), n.orgId().trim(), Money.of(n.amountMinor(), n.currency()),
                    ProviderFee.LATE_PROVIDER_CANCELLATION, n.eventId(), n.occurredAt(), now);
            if (!fees.insert(fee)) {
                LOG.info("Booking {} already has a provider fee; event {} ignored", n.bookingId(), n.eventId());
                return false;
            }
            ledger.post(Postings.providerFee(Fingerprint.id("journal", "provider-fee", fee.id().toString()), now,
                    "provider-fee:" + fee.id(), fee), SYSTEM);
            publish(PaymentsEvents.PROVIDER_FEE_RECORDED, fee);
            collect(fee, now);
            return true;
        });
    }

    @Override
    public int collectOutstanding(int maxFees) {
        int collected = 0;
        for (int i = 0; i < maxFees; i++) {
            Boolean result = tx.serializable(() -> {
                Instant now = Instant.now(clock);
                Optional<ProviderFee> due = fees.lockNextDue(now);
                return due.map(f -> collect(f, now)).orElse(null);
            });
            if (result == null) {
                break;
            }
            collected += result ? 1 : 0;
        }
        return collected;
    }

    @Override
    public List<ProviderFee> ofOrg(String orgId) {
        String org = access.orgOf(orgId);
        access.balanceViewer(org);
        return fees.ofOrg(org, MAX_LIST);
    }

    /** One collection attempt of a locked OUTSTANDING fee; failures are recorded for a later retry. */
    private boolean collect(ProviderFee fee, Instant now) {
        Optional<String> account = accounts.find(fee.orgId());
        if (account.isEmpty()) {
            fees.update(fee.attemptFailed("Organisation has no connected account", true, now));
            return false;
        }
        StripeGateway.AccountDebitSnapshot debit;
        try {
            debit = stripe.debitConnectedAccount(account.get(), fee.amount(), "AfroTransact late cancellation fee "
                    + fee.bookingReference(), Map.of("provider_fee_id", fee.id().toString(), "booking_id",
                    fee.bookingId(), "org_id", fee.orgId()), fee.debitKey());
        } catch (StripeGatewayException e) {
            LOG.warn("Provider fee {} of {}: account debit failed ({}); will retry", fee.id(), fee.orgId(),
                    e.getMessage());
            fees.update(fee.attemptFailed(e.getMessage(), e.clientError(), now));
            return false;
        }
        ledger.post(Postings.providerFeeCollected(Fingerprint.id("journal", "provider-fee-collected",
                fee.id().toString()), now, "provider-fee-collected:" + fee.id(), fee, debit.id()), SYSTEM);
        ProviderFee stored = fees.update(fee.collected(debit.id(), now));
        publish(PaymentsEvents.PROVIDER_FEE_COLLECTED, stored);
        return true;
    }

    private void publish(String type, ProviderFee fee) {
        events.publish(type, PaymentsEvents.AGGREGATE_PAYOUT, fee.orgId(),
                PaymentsEventPayloads.ProviderFeePayload.of(fee));
    }
}
