package com.afrotransact.bookings.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.bookings.domain.BalanceCharge;
import com.afrotransact.bookings.domain.BalanceChargePolicy;
import com.afrotransact.bookings.domain.Booking;
import com.afrotransact.bookings.domain.MoneyMove;
import com.afrotransact.bookings.domain.Transition;
import com.afrotransact.bookings.port.BookingRepository;
import com.afrotransact.bookings.port.PaymentsClient;
import com.afrotransact.bookings.port.PaymentsClientException;
import com.afrotransact.bookings.port.ProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class BalanceChargeServiceImpl implements BalanceChargeService {

    private static final Logger LOG = LoggerFactory.getLogger(BalanceChargeServiceImpl.class);
    private static final int BATCH = 100;
    /** payments' answer while an earlier attempt of the same charge is still being processed: try again later. */
    private static final String IN_FLIGHT = "payments.payment-in-flight";

    private final BookingRepository bookings;
    private final ProviderRepository providers;
    private final PaymentsClient payments;
    private final BookingMoney money;
    private final BookingEventPublisher events;
    private final BookingsSettings settings;
    private final TransactionTemplate tx;
    private final Clock clock;

    BalanceChargeServiceImpl(BookingRepository bookings, ProviderRepository providers, PaymentsClient payments,
                             BookingMoney money, BookingEventPublisher events, BookingsSettings settings,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.bookings = bookings;
        this.providers = providers;
        this.payments = payments;
        this.money = money;
        this.events = events;
        this.settings = settings;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public int chargeDueBalances() {
        BalanceChargePolicy policy = settings.balance();
        Instant now = Instant.now(clock);
        List<UUID> due = bookings.dueForBalanceCharge(now.plus(policy.chargeBefore()), BATCH);
        int changed = 0;
        for (UUID id : due) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> chargeOne(id, policy)))) {
                    changed++;
                }
            } catch (RuntimeException e) {
                // payments unavailable or an optimistic conflict: nothing was saved, the same attempt (same key)
                // is replayed next round
                LOG.warn("Balance of booking {} not charged this round; will retry: {}", id, e.getMessage());
            }
        }
        return changed;
    }

    private boolean chargeOne(UUID id, BalanceChargePolicy policy) {
        Booking b = bookings.tryLock(id).orElse(null);
        if (b == null) {
            return false;
        }
        Instant now = Instant.now(clock);
        BalanceCharge before = b.balance();
        Transition t = switch (policy.decide(b, now)) {
            case SKIP, NOT_YET, WAIT_RETRY -> null;
            case GIVE_UP -> b.balanceGiveUp(now);
            case CHARGE -> charge(b, policy, now);
        };
        if (t == null || !t.changed() && before.equals(b.balance())) {
            return false;
        }
        Transition done = money.apply(b, t, now);
        Booking saved = bookings.update(b);
        events.publish(done, saved, providers.find(saved.orgId()).orElseThrow());
        return true;
    }

    private Transition charge(Booking b, BalanceChargePolicy policy, Instant now) {
        int attempt = b.balance().attempts() + 1;
        String key = b.reference() + ":offsession:" + attempt;
        try {
            PaymentsClient.Intent charge = payments.chargeOffSession(b.paymentId(),
                    new PaymentsClient.OffSessionCharge(b.customerId(), null, List.of(MoneyMove.BALANCE)), key);
            LOG.info("Balance of {} charged off-session (attempt {}): {}", b.reference(), attempt, charge.status());
            return b.balanceChargeSubmitted(attempt, charge.id(), charge.status(), charge.clientSecret(),
                    charge.failureMessage(), policy, now);
        } catch (PaymentsClientException e) {
            if (!e.rejected() || IN_FLIGHT.equals(e.code())) {
                throw e;
            }
            LOG.warn("Off-session balance charge of {} refused (attempt {}): {} {}", b.reference(), attempt,
                    e.code(), e.getMessage());
            return b.balanceChargeRefused(attempt, e.code() == null ? e.getMessage() : e.code(), policy, now);
        }
    }
}
