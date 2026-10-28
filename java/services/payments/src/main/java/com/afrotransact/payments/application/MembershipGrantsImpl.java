package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.Membership;
import com.afrotransact.payments.domain.MembershipEvent;
import com.afrotransact.payments.domain.MembershipTier;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.domain.TierStatus;
import com.afrotransact.payments.port.MembershipActivityRepository;
import com.afrotransact.payments.port.MembershipRepository;
import com.afrotransact.payments.port.MembershipTierRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
class MembershipGrantsImpl implements MembershipGrants {

    static final int MAX_DAYS = 365;

    private final MembershipRepository memberships;
    private final MembershipTierRepository tiers;
    private final MembershipActivityRepository activity;
    private final MembershipRecorder recorder;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final Clock clock;

    MembershipGrantsImpl(MembershipRepository memberships, MembershipTierRepository tiers,
                         MembershipActivityRepository activity, MembershipRecorder recorder, MoneyTransactions tx,
                         PaymentsAccess access, Clock clock) {
        this.memberships = memberships;
        this.tiers = tiers;
        this.activity = activity;
        this.recorder = recorder;
        this.tx = tx;
        this.access = access;
        this.clock = clock;
    }

    @Override
    public Grant grantDaysForProgram(String customerId, String tierCode, int days, String reason,
                                     String idempotencyKey) {
        access.creditGranter();
        return grantDays(customerId, tierCode, days, reason, idempotencyKey);
    }

    @Override
    public Grant grantDays(String customerId, String tierCode, int days, String reason, String idempotencyKey) {
        if (customerId == null || customerId.isBlank()) {
            throw new PaymentsRuleViolation("payments.invalid-request", "Name the customer",
                    Map.of("customerId", "Required"));
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PaymentsRuleViolation("payments.idempotency-key-required", "Grants need an idempotency key",
                    Map.of("idempotencyKey", "Required"));
        }
        if (days < 1 || days > MAX_DAYS) {
            throw new PaymentsRuleViolation("payments.membership-grant-days", "Grant between 1 and " + MAX_DAYS
                    + " days", Map.of("days", "Between 1 and " + MAX_DAYS));
        }
        if (reason == null || reason.trim().length() < 5) {
            throw new PaymentsRuleViolation("payments.reason-required", "Say why", Map.of("reason", "Required"));
        }
        String customer = customerId.trim();
        String key = "grant:" + idempotencyKey.trim();
        return tx.serializable(() -> {
            MembershipTier tier = tiers.find(tierCode == null ? "" : tierCode).orElseThrow(() -> new NotFoundException(
                    "payments.membership-tier-not-found", "No membership tier " + tierCode));
            if (tier.status() == TierStatus.DRAFT) {
                throw new PaymentsStateConflict("payments.membership-tier-unavailable", tier.name()
                        + " is not published yet");
            }
            Optional<UUID> prior = activity.grantMembership(key);
            if (prior.isPresent()) {
                return replay(prior.get(), customer, tier, days);
            }
            Instant now = Instant.now(clock);
            Optional<Membership> live = memberships.lockLiveOf(customer);
            Membership m;
            boolean created = live.isEmpty();
            if (created) {
                m = Membership.complimentary(Fingerprint.id("membership-grant", key), customer, tier, days, now);
                memberships.insert(m);
                m = memberships.find(m.id()).orElseThrow();
            } else {
                m = memberships.update(live.get().extended(days, now));
            }
            activity.claimGrant(key, customer, tier.code(), days, reason.trim(), "program", m.id(), now);
            recorder.record(m, MembershipEvent.Type.FREE_DAYS_GRANTED, "program", days + " days of " + tier.name()
                    + ": " + reason.trim(), 0);
            return new Grant(m.id(), customer, tier.code(), days, false, m.periodEnd(), created);
        });
    }

    private Grant replay(UUID membershipId, String customer, MembershipTier tier, int days) {
        Membership m = memberships.find(membershipId).orElseThrow();
        if (!m.customerId().equals(customer)) {
            throw new PaymentsStateConflict("payments.membership-grant-key-reused", "That idempotency key was used "
                    + "for another customer");
        }
        return new Grant(m.id(), customer, tier.code(), days, true, m.periodEnd(), false);
    }
}
