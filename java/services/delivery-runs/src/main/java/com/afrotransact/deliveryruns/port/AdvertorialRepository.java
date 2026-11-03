package com.afrotransact.deliveryruns.port;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AdvertorialSource;
import com.afrotransact.deliveryruns.domain.AdvertorialStatus;
import com.afrotransact.deliveryruns.domain.LocalAdvertorial;

/** Location advertorials and their view / click counters (S263). */
public interface AdvertorialRepository {

    /** @throws DuplicateEntryException when the source already has a variant with this label */
    LocalAdvertorial insert(LocalAdvertorial advertorial);

    /** Optimistic: fails with {@link ConcurrentUpdateException} when {@code advertorial.version()} is stale. */
    LocalAdvertorial update(LocalAdvertorial advertorial);

    Optional<LocalAdvertorial> findById(UUID id);

    /** All variants of one window or run, by variant. */
    List<LocalAdvertorial> findBySource(AdvertorialSource source, UUID sourceId);

    /** The staff list, newest first; null filters match everything. */
    List<LocalAdvertorial> find(AdvertorialSource source, UUID sourceId, AdvertorialStatus status, int limit);

    /** The LIVE advertorials of these windows or runs. */
    List<LocalAdvertorial> findLive(Collection<UUID> sourceIds);

    /** At most {@code limit} LIVE advertorials of any window or run, oldest first (the sweeper's worklist). */
    List<LocalAdvertorial> findLive(int limit);

    /** Adds to the counters of the advertorial's UTC day. */
    void count(UUID advertorialId, LocalDate day, long views, long clicks);

    /**
     * Lets one view or click through the rate limit: at most {@code max} per advertorial, subject and kind in the
     * window that started at the subject's first admitted hit (a later hit after the window opens a new one).
     * Atomic, so concurrent posts cannot both pass the last place.
     *
     * @param subject a hash of the visitor id or the client address
     * @param kind    VIEW or CLICK
     * @return whether this hit may be counted
     */
    boolean admit(UUID advertorialId, String subject, String kind, Instant now, Duration window, int max);

    /** Forgets the rate-limit rows whose window ended before {@code before}; returns how many. */
    int purgeGates(Instant before);

    /** Views and clicks per advertorial (advertorials without any are absent). */
    Map<UUID, Counters> counters(Collection<UUID> advertorialIds);

    /**
     * @param views  times it was shown
     * @param clicks times its button was clicked
     */
    record Counters(long views, long clicks) {
    }
}
