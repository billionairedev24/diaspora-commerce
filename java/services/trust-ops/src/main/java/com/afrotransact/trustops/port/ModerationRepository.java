package com.afrotransact.trustops.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.trustops.domain.ModerationItem;
import com.afrotransact.trustops.domain.ModerationItemType;
import com.afrotransact.trustops.domain.ModerationStatus;

/** Moderation queue items. */
public interface ModerationRepository {

    ModerationItem insert(ModerationItem item);

    /** Optimistic update; throws a conflict when the item changed since it was read. */
    ModerationItem update(ModerationItem item);

    Optional<ModerationItem> findById(UUID id);

    Optional<ModerationItem> findOpenByProduct(UUID productId);

    /** The open item of a booking review (the booking id is the review id). */
    Optional<ModerationItem> findOpenByReview(UUID bookingId);

    /**
     * Queue order: soonest due first.
     *
     * @param status   null = open items (PENDING and ESCALATED)
     * @param assignee null = anyone
     * @param type     null = every type (new and live listings, service reviews)
     */
    Page<ModerationItem> search(ModerationStatus status, String assignee, ModerationItemType type, int page,
                                int size);

    /** PENDING items whose SLA is breached at {@code now}. */
    List<ModerationItem> findBreachedPending(Instant now, int limit);
}
