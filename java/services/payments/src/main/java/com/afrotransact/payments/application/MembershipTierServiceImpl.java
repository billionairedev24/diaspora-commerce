package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.payments.domain.MembershipStatus;
import com.afrotransact.payments.domain.MembershipTier;
import com.afrotransact.payments.domain.PaymentsRuleViolation;
import com.afrotransact.payments.domain.PaymentsStateConflict;
import com.afrotransact.payments.domain.TierStatus;
import com.afrotransact.payments.port.MembershipRepository;
import com.afrotransact.payments.port.MembershipTierRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
class MembershipTierServiceImpl implements MembershipTierService {

    static final int MIN_REASON = 15;

    private final MembershipTierRepository tiers;
    private final MembershipRepository memberships;
    private final MoneyTransactions tx;
    private final PaymentsAccess access;
    private final PaymentsSettings settings;
    private final DomainEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;

    MembershipTierServiceImpl(MembershipTierRepository tiers, MembershipRepository memberships, MoneyTransactions tx,
                              PaymentsAccess access, PaymentsSettings settings, DomainEventPublisher events,
                              JsonMapper json, Clock clock) {
        this.tiers = tiers;
        this.memberships = memberships;
        this.tx = tx;
        this.access = access;
        this.settings = settings;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public List<MembershipTier> published() {
        access.current();
        return tiers.published();
    }

    @Override
    public List<TierOverview> overview() {
        access.reader();
        Map<String, long[]> counts = new HashMap<>();
        for (MembershipRepository.TierCount c : memberships.countsByTier()) {
            long[] n = counts.computeIfAbsent(c.tierCode(), k -> new long[2]);
            n[0] += c.count();
            if (c.status() == MembershipStatus.TRIALING) {
                n[1] += c.count();
            }
        }
        return tiers.all().stream().map(t -> {
            long[] n = counts.getOrDefault(t.code(), new long[2]);
            return new TierOverview(t, n[0], n[1]);
        }).toList();
    }

    @Override
    public MembershipTier create(SaveTier c) {
        Actor actor = access.configure();
        requireReason(c.reason());
        return tx.serializable(() -> {
            if (tiers.find(c.code() == null ? "" : c.code()).isPresent()) {
                throw new PaymentsStateConflict("payments.membership-tier-exists",
                        "There is already a tier with the code " + c.code());
            }
            Instant now = Instant.now(clock);
            MembershipTier tier = new MembershipTier(c.code(), c.name(), c.description(), c.status(), c.sortOrder(),
                    settings.currency(), c.monthlyPriceMinor(), c.yearlyPriceMinor(), c.trialDays(), c.benefits(),
                    now, actor.subject(), now, 0);
            tiers.insert(tier);
            audit(tier, null, "CREATED", c.reason(), actor, now);
            return tier;
        });
    }

    @Override
    public MembershipTier update(SaveTier c) {
        Actor actor = access.configure();
        requireReason(c.reason());
        return tx.serializable(() -> {
            MembershipTier before = tiers.lock(c.code() == null ? "" : c.code()).orElseThrow(() ->
                    new NotFoundException("payments.membership-tier-not-found", "No membership tier " + c.code()));
            if (c.expectedVersion() == null || c.expectedVersion() != before.version()) {
                throw new PaymentsStateConflict("payments.membership-tier-changed",
                        "The tier was changed by someone else; reload and try again");
            }
            if (c.status() == null) {
                throw new PaymentsRuleViolation("payments.membership-tier-invalid", "Invalid membership tier",
                        Map.of("status", "Required"));
            }
            if (before.status() != TierStatus.DRAFT && c.status() == TierStatus.DRAFT) {
                throw new PaymentsRuleViolation("payments.membership-tier-invalid", "A published tier cannot "
                        + "go back to draft; retire it instead", Map.of("status", "Retire instead of draft"));
            }
            Instant now = Instant.now(clock);
            MembershipTier next = before.changed(c.name(), c.description(), c.status(), c.sortOrder(),
                    c.monthlyPriceMinor(), c.yearlyPriceMinor(), c.trialDays(), c.benefits(), actor.subject(), now);
            MembershipTier stored = tiers.update(next, before.version());
            String action = before.status() == c.status() ? "UPDATED"
                    : c.status() == TierStatus.RETIRED ? "RETIRED" : "PUBLISHED";
            audit(stored, before, action, c.reason(), actor, now);
            return stored;
        });
    }

    @Override
    public List<MembershipTierRepository.Change> changes(String code, int limit) {
        access.reader();
        return tiers.changes(code, Math.max(1, Math.min(limit, 200)));
    }

    private void audit(MembershipTier after, MembershipTier before, String action, String reason, Actor actor,
                       Instant now) {
        tiers.addChange(new MembershipTierRepository.Change(UUID.randomUUID(), after.code(), action,
                actor.subject(), now, reason.trim(), before == null ? null : json.writeValueAsString(before),
                json.writeValueAsString(after)));
        events.publish(PaymentsEvents.MEMBERSHIP_TIER_CHANGED, PaymentsEvents.AGGREGATE_PAYMENT, after.code(),
                new PaymentsEventPayloads.MembershipTierChangedPayload(after.code(), action, after.status().name(),
                        reason.trim(), actor.subject()));
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.trim().length() < MIN_REASON) {
            throw new PaymentsRuleViolation("payments.reason-required",
                    "Say why (at least " + MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
    }
}
