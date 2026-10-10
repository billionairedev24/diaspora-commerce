package com.afrotransact.deliveryruns.domain;

import java.time.Instant;

/** When each lifecycle step of a run happened (null = not yet). */
public record RunMilestones(Instant announcedAt, Instant openedAt, Instant cutOffAt, Instant confirmedAt,
                            Instant collectingAt, Instant outForDeliveryAt, Instant completedAt, Instant cancelledAt) {

    public static final RunMilestones NONE = new RunMilestones(null, null, null, null, null, null, null, null);

    RunMilestones announced(Instant at) {
        return new RunMilestones(at, openedAt, cutOffAt, confirmedAt, collectingAt, outForDeliveryAt, completedAt,
                cancelledAt);
    }

    RunMilestones opened(Instant at) {
        return new RunMilestones(announcedAt, at, cutOffAt, confirmedAt, collectingAt, outForDeliveryAt, completedAt,
                cancelledAt);
    }

    RunMilestones cutOff(Instant at) {
        return new RunMilestones(announcedAt, openedAt, at, confirmedAt, collectingAt, outForDeliveryAt, completedAt,
                cancelledAt);
    }

    RunMilestones confirmed(Instant at) {
        return new RunMilestones(announcedAt, openedAt, cutOffAt, at, collectingAt, outForDeliveryAt, completedAt,
                cancelledAt);
    }

    RunMilestones collecting(Instant at) {
        return new RunMilestones(announcedAt, openedAt, cutOffAt, confirmedAt, at, outForDeliveryAt, completedAt,
                cancelledAt);
    }

    RunMilestones outForDelivery(Instant at) {
        return new RunMilestones(announcedAt, openedAt, cutOffAt, confirmedAt, collectingAt, at, completedAt,
                cancelledAt);
    }

    RunMilestones completed(Instant at) {
        return new RunMilestones(announcedAt, openedAt, cutOffAt, confirmedAt, collectingAt, outForDeliveryAt, at,
                cancelledAt);
    }

    RunMilestones cancelled(Instant at) {
        return new RunMilestones(announcedAt, openedAt, cutOffAt, confirmedAt, collectingAt, outForDeliveryAt,
                completedAt, at);
    }
}
