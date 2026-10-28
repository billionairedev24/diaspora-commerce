package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.application.MembershipCharger.Outcome;
import com.afrotransact.payments.domain.BenefitCode;
import com.afrotransact.payments.domain.BillingInterval;
import com.afrotransact.payments.domain.Membership;
import com.afrotransact.payments.domain.MembershipCharge;
import com.afrotransact.payments.domain.MembershipEvent;
import com.afrotransact.payments.domain.MembershipSettings;
import com.afrotransact.payments.domain.MembershipStatus;
import com.afrotransact.payments.domain.MembershipTier;
import com.afrotransact.payments.domain.Money;
import com.afrotransact.payments.port.MembershipActivityRepository;
import com.afrotransact.payments.port.MembershipRepository;
import com.afrotransact.payments.port.MembershipSettingsRepository;
import com.afrotransact.payments.port.MembershipTierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class MembershipBillingServiceImpl implements MembershipBillingService {

    private static final Logger LOG = LoggerFactory.getLogger(MembershipBillingServiceImpl.class);
    private static final String SYSTEM = "system:membership";
    private static final Duration CASHBACK_SWEEP_WINDOW = Duration.ofDays(2);

    private final MembershipRepository memberships;
    private final MembershipTierRepository tiers;
    private final MembershipSettingsRepository settings;
    private final MembershipActivityRepository activity;
    private final MembershipCharger charger;
    private final MembershipCredits credits;
    private final MembershipRecorder recorder;
    private final MembershipBenefitsService benefits;
    private final MoneyTransactions tx;
    private final Clock clock;

    MembershipBillingServiceImpl(MembershipRepository memberships, MembershipTierRepository tiers,
                                 MembershipSettingsRepository settings, MembershipActivityRepository activity,
                                 MembershipCharger charger, MembershipCredits credits, MembershipRecorder recorder,
                                 MembershipBenefitsService benefits, MoneyTransactions tx, Clock clock) {
        this.memberships = memberships;
        this.tiers = tiers;
        this.settings = settings;
        this.activity = activity;
        this.charger = charger;
        this.credits = credits;
        this.recorder = recorder;
        this.benefits = benefits;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public int renewDue(int max) {
        int processed = 0;
        for (UUID id : memberships.dueIds(Instant.now(clock), max)) {
            try {
                Boolean did = tx.serializable(() -> {
                    Instant now = Instant.now(clock);
                    Optional<Membership> locked = memberships.lock(id).filter(m -> m.dueAt(now));
                    locked.ifPresent(m -> process(m, now));
                    return locked.isPresent();
                });
                if (did) {
                    processed++;
                }
            } catch (RuntimeException e) {
                // one membership's trouble (Stripe unreachable, a tier gone) must not hold up the others
                LOG.error("Renewing membership {} failed; it is tried again on the next run", id, e);
            }
        }
        return processed;
    }

    /** One due membership, inside the transaction that holds its row. */
    private void process(Membership m, Instant now) {
        if (m.cancelAtPeriodEnd()) {
            Membership ended = memberships.update(m.ended(MembershipStatus.CANCELED, Membership.ENDED_CANCELED, now));
            recorder.record(ended, MembershipEvent.Type.ENDED, SYSTEM, "Canceled by the member; the paid period is "
                    + "over", 0);
            return;
        }
        if (!m.paidByCard()) {
            Membership ended = memberships.update(m.ended(MembershipStatus.EXPIRED, Membership.ENDED_FREE_DAYS_OVER,
                    now));
            recorder.record(ended, MembershipEvent.Type.ENDED, SYSTEM, "Complimentary days are over", 0);
            return;
        }
        MembershipSettings rules = settings.get();
        boolean wasTrial = m.status() == MembershipStatus.TRIALING;
        boolean changing = m.pendingTierCode() != null;
        String nextTier = changing ? m.pendingTierCode() : m.tierCode();
        BillingInterval nextInterval = changing ? m.pendingInterval() : m.interval();
        Optional<MembershipTier> tier = tiers.find(nextTier);
        if (tier.isEmpty()) {
            LOG.error("Membership {} is on tier {} which no longer exists; not billing it", m.id(), nextTier);
            return;
        }
        long price = changing ? tier.get().priceMinor(nextInterval) : m.priceMinor();
        int attempt = m.retryCount() + 1;
        Instant start = m.periodEnd();
        Instant end = nextInterval.periodEnd(start);
        Outcome outcome = charger.charge(new MembershipCharger.Request(m, MembershipCharge.Kind.RENEWAL, attempt,
                Money.of(price, m.currency()), start, end, "membership:" + m.id() + ":renew:"
                + m.periodEnd().getEpochSecond() + ":" + attempt), true);
        if (outcome.paid()) {
            Membership renewed = memberships.update(m.renewed(nextTier, nextInterval, price, now));
            recorder.record(renewed, wasTrial ? MembershipEvent.Type.TRIAL_CONVERTED : MembershipEvent.Type.RENEWED,
                    SYSTEM, tier.get().name() + " until " + renewed.periodEnd(), price);
            if (changing) {
                recorder.record(renewed, MembershipEvent.Type.DOWNGRADED, SYSTEM, "Moved to " + tier.get().name(), 0);
            }
            credits.monthly(renewed, tier.get(), now);
            return;
        }
        Membership failed = memberships.update(m.renewalFailed(rules.retryDays(), now));
        recorder.record(failed, MembershipEvent.Type.RENEWAL_FAILED, SYSTEM, outcome.code() == null ? "declined"
                : outcome.code(), price);
        if (failed.status() == MembershipStatus.LAPSED) {
            recorder.record(failed, MembershipEvent.Type.LAPSED, SYSTEM, "Renewal failed " + failed.retryCount()
                    + " times", 0);
        } else {
            recorder.record(failed, MembershipEvent.Type.PAST_DUE, SYSTEM, "Next try " + failed.nextRetryAt(), 0);
        }
    }

    @Override
    public int grantMonthlyCredits(int max) {
        int granted = 0;
        UUID after = null;
        while (granted < max) {
            List<Membership> page = memberships.liveForMonthlyCredit(100, after);
            if (page.isEmpty()) {
                break;
            }
            for (Membership m : page) {
                after = m.id();
                Optional<MembershipTier> tier = tiers.find(m.tierCode()).filter(t -> t.has(BenefitCode.MONTHLY_CREDIT));
                if (tier.isEmpty()) {
                    continue;
                }
                Boolean did = tx.serializable(() -> credits.monthly(m, tier.get(), Instant.now(clock)).isPresent());
                if (did) {
                    granted++;
                }
            }
        }
        return granted;
    }

    @Override
    public int grantDueCashback(int max) {
        Instant since = Instant.now(clock).minus(CASHBACK_SWEEP_WINDOW);
        List<String> orders = activity.deliveredOrdersAwaitingCashback(since, max);
        orders.forEach(benefits::onOrderDelivered);
        return orders.size();
    }
}
