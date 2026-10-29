package com.afrotransact.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.afrotransact.payments.domain.Membership;
import com.afrotransact.payments.domain.MembershipEvent;
import com.afrotransact.payments.port.MembershipActivityRepository;
import com.afrotransact.payments.port.MembershipTierRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Writes a step of a membership's life to its timeline (churn is counted from it) and publishes
 * {@code payments.membership.changed} so notifications and analytics can follow. Runs in the caller's transaction.
 */
@Component
class MembershipRecorder {

    private final MembershipActivityRepository activity;
    private final MembershipTierRepository tiers;
    private final DomainEventPublisher events;
    private final Clock clock;

    MembershipRecorder(MembershipActivityRepository activity, MembershipTierRepository tiers,
                       DomainEventPublisher events, Clock clock) {
        this.activity = activity;
        this.tiers = tiers;
        this.events = events;
        this.clock = clock;
    }

    void record(Membership m, MembershipEvent.Type type, String actor, String detail, long amountMinor) {
        Instant now = Instant.now(clock);
        activity.addEvent(new MembershipEvent(UUID.randomUUID(), m.id(), m.customerId(), type, m.tierCode(), actor,
                detail, now));
        String name = tiers.find(m.tierCode()).map(t -> t.name()).orElse(m.tierCode());
        events.publish(PaymentsEvents.MEMBERSHIP_CHANGED, PaymentsEvents.AGGREGATE_PAYMENT, m.customerId(),
                new PaymentsEventPayloads.MembershipChangedPayload(m.id().toString(), m.customerId(), type.name(),
                        m.tierCode(), name, m.status().name(), m.interval().name(), m.periodEnd(), amountMinor,
                        m.currency(), detail));
    }
}
