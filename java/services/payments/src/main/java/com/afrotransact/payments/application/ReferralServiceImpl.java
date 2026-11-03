package com.afrotransact.payments.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.Referral;
import com.afrotransact.payments.domain.ReferralCodes;
import com.afrotransact.payments.domain.ReferralRewardGrant;
import com.afrotransact.payments.domain.ReferralStatus;
import com.afrotransact.payments.port.ReferralRepository;
import com.afrotransact.payments.port.ReferralSettingsRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class ReferralServiceImpl implements ReferralService {

    private static final int MIN_REASON = 15;
    private static final int MAX_LIST = 500;
    private static final int CODE_ATTEMPTS = 20;

    private final ReferralRepository referrals;
    private final ReferralSettingsRepository settings;
    private final ReferralRewarder rewarder;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final PaymentsSettings payments;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    ReferralServiceImpl(ReferralRepository referrals, ReferralSettingsRepository settings, ReferralRewarder rewarder,
                        MoneyTransactions tx, PaymentsAccess access, PaymentsSettings payments,
                        DomainEventPublisher events, Clock clock) {
        this.referrals = referrals;
        this.settings = settings;
        this.rewarder = rewarder;
        this.tx = tx;
        this.access = access;
        this.payments = payments;
        this.events = events;
        this.clock = clock;
    }

    @Override
    public Overview myReferral() {
        String customer = access.customer().subject();
        String code = codeOf(customer);
        Optional<Entry> asFriend = referrals.findByReferred(customer).map(this::entry);
        return new Overview(code, ReferralCodes.display(code), settings.get(), payments.currency(),
                referrals.earnings(customer), asFriend);
    }

    @Override
    public List<Entry> myReferrals(int limit) {
        String customer = access.customer().subject();
        List<Referral> mine = referrals.ofReferrer(customer, clamp(limit));
        return entries(mine);
    }

    @Override
    public List<Entry> list(ReferralStatus status, String customerId, int limit) {
        access.reader();
        return entries(referrals.search(status, customerId == null || customerId.isBlank() ? null
                : customerId.trim(), clamp(limit)));
    }

    @Override
    public Entry get(UUID id) {
        access.reader();
        return entry(referrals.find(id).orElseThrow(() -> notFound(id)));
    }

    @Override
    public Summary summary() {
        access.reader();
        return new Summary(referrals.totals(), settings.get(), payments.currency());
    }

    @Override
    public Entry approve(UUID id, String reason) {
        Actor staff = access.adjuster();
        String why = requireReason(reason);
        return tx.serializable(() -> {
            Referral locked = referrals.lock(id).orElseThrow(() -> notFound(id));
            Instant now = Instant.now(clock);
            Referral approved = referrals.update(locked.approved(staff.subject(), why, now));
            events.publish(PaymentsEvents.REFERRAL_DECIDED, PaymentsEvents.AGGREGATE_PAYMENT, id.toString(),
                    new PaymentsEventPayloads.ReferralDecidedPayload(id.toString(), approved.referrerId(),
                            approved.referredId(), approved.decision().name(), why, staff.subject()));
            return entry(rewarder.settle(approved, now));
        });
    }

    @Override
    public Entry reject(UUID id, String reason) {
        Actor staff = access.adjuster();
        String why = requireReason(reason);
        return tx.serializable(() -> {
            Referral locked = referrals.lock(id).orElseThrow(() -> notFound(id));
            return entry(rewarder.rejectByStaff(locked, staff.subject(), why, Instant.now(clock)));
        });
    }

    // ------------------------------------------------------------------ helpers

    /** The customer's code; created on first use. Two requests at once end with the same code. */
    private String codeOf(String customerId) {
        Optional<String> existing = referrals.codeOf(customerId);
        if (existing.isPresent()) {
            return existing.get();
        }
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            referrals.insertCode(customerId, ReferralCodes.generate(random), Instant.now(clock));
            Optional<String> stored = referrals.codeOf(customerId);
            if (stored.isPresent()) {
                return stored.get();
            }
        }
        throw new IllegalStateException("Could not allocate a referral code");
    }

    private List<Entry> entries(List<Referral> list) {
        Map<UUID, List<ReferralRewardGrant>> rewards = referrals.rewardsOf(list.stream().map(Referral::id).toList());
        return list.stream().map(r -> new Entry(r, rewards.getOrDefault(r.id(), List.of()))).toList();
    }

    private Entry entry(Referral r) {
        return new Entry(r, referrals.rewards(r.id()));
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.trim().length() < MIN_REASON) {
            throw new PaymentsRuleViolation("payments.reason-required",
                    "Say why (at least " + MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        return reason.trim();
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIST));
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("payments.referral-not-found", "No referral " + id);
    }
}
