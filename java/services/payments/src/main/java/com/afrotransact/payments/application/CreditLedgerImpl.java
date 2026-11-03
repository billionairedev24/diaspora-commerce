package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.CreditEntry;
import com.afrotransact.payments.domain.CreditKind;
import com.afrotransact.payments.domain.CreditSource;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.domain.Postings;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.port.CreditLedgerRepository;
import org.springframework.stereotype.Component;

@Component
class CreditLedgerImpl implements CreditLedger {

    private final CreditLedgerRepository entries;
    private final LedgerPoster poster;
    private final MoneyTransactions tx;
    private final Clock clock;

    CreditLedgerImpl(CreditLedgerRepository entries, LedgerPoster poster, MoneyTransactions tx, Clock clock) {
        this.entries = entries;
        this.poster = poster;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public CreditEntry add(Add c) {
        requirePositive(c.amount());
        if (c.kind() == CreditKind.SPEND) {
            throw new IllegalArgumentException("Credit is spent through spend()");
        }
        return tx.serializable(() -> {
            Instant now = Instant.now(clock);
            long balance = entries.lockBalance(c.customerId(), c.amount().currency(), now);
            Optional<CreditEntry> prior = entries.findByKey(c.idempotencyKey());
            if (prior.isPresent()) {
                return sameAs(prior.get(), c.customerId(), c.amount().minor());
            }
            CreditEntry entry = new CreditEntry(UUID.randomUUID(), c.customerId(), c.kind(), c.amount().minor(),
                    c.amount().currency(), c.source(), c.reason(), c.actor(), c.orderId(), c.reference(),
                    c.idempotencyKey(), Math.addExact(balance, c.amount().minor()), now);
            entries.append(entry);
            if (c.postExpense()) {
                poster.post(Postings.creditGranted(Fingerprint.id("journal", "credit-grant", entry.id().toString()),
                        now, "credit-grant:" + entry.id(), c.customerId(), c.amount(), c.reason()), c.actor());
            }
            return entry;
        });
    }

    @Override
    public Spend spend(SpendCommand c) {
        requirePositive(c.maxAmount());
        return tx.serializable(() -> {
            Instant now = Instant.now(clock);
            String currency = c.maxAmount().currency();
            long balance = entries.lockBalance(c.customerId(), currency, now);
            Optional<CreditEntry> prior = entries.findByKey(c.idempotencyKey());
            if (prior.isPresent()) {
                CreditEntry first = prior.get();
                if (!first.customerId().equals(c.customerId()) || first.kind() != CreditKind.SPEND) {
                    throw conflict(c.idempotencyKey());
                }
                return new Spend(Money.of(-first.amountMinor(), currency), first, balance);
            }
            long spend = Math.min(balance, c.maxAmount().minor());
            long remainder = c.maxAmount().minor() - spend;
            if (remainder > 0 && remainder < c.minRemainderMinor()) {
                spend = Math.max(0, c.maxAmount().minor() - c.minRemainderMinor());
            }
            if (spend <= 0) {
                return new Spend(Money.zero(currency), null, balance);
            }
            CreditEntry entry = new CreditEntry(UUID.randomUUID(), c.customerId(), CreditKind.SPEND, -spend,
                    currency, CreditSource.ORDER, "Paid part of order " + c.orderId(), c.actor(), c.orderId(), null,
                    c.idempotencyKey(), balance - spend, now);
            entries.append(entry);
            return new Spend(Money.of(spend, currency), entry, balance - spend);
        });
    }

    @Override
    public CreditEntry take(Take c) {
        requirePositive(c.amount());
        return tx.serializable(() -> {
            Instant now = Instant.now(clock);
            long balance = entries.lockBalance(c.customerId(), c.amount().currency(), now);
            Optional<CreditEntry> prior = entries.findByKey(c.idempotencyKey());
            if (prior.isPresent()) {
                return sameAs(prior.get(), c.customerId(), -c.amount().minor());
            }
            if (balance < c.amount().minor()) {
                throw new PaymentsRuleViolation("payments.credit-insufficient",
                        "The customer has only " + Money.of(balance, c.amount().currency()) + " of credit",
                        Map.of("amountMinor", "More than the balance"));
            }
            CreditEntry entry = new CreditEntry(UUID.randomUUID(), c.customerId(), CreditKind.ADJUSTMENT,
                    -c.amount().minor(), c.amount().currency(), CreditSource.STAFF, c.reason(), c.actor(), null,
                    c.reference(), c.idempotencyKey(), balance - c.amount().minor(), now);
            entries.append(entry);
            poster.post(Postings.creditTakenBack(Fingerprint.id("journal", "credit-taken", entry.id().toString()),
                    now, "credit-taken:" + entry.id(), c.customerId(), c.amount(), c.reason()), c.actor());
            return entry;
        });
    }

    @Override
    public long balance(String customerId) {
        return entries.balance(customerId);
    }

    private static void requirePositive(Money amount) {
        if (!amount.isPositive()) {
            throw new PaymentsRuleViolation("payments.credit-amount", "The credit amount must be above zero",
                    Map.of("amountMinor", "Must be > 0"));
        }
    }

    private static CreditEntry sameAs(CreditEntry first, String customerId, long signedMinor) {
        if (!first.customerId().equals(customerId) || first.amountMinor() != signedMinor) {
            throw conflict(first.idempotencyKey());
        }
        return first;
    }

    private static PaymentsStateConflict conflict(String key) {
        return new PaymentsStateConflict("payments.credit-key-reused", "Idempotency key " + key
                + " was used for another credit operation");
    }
}
