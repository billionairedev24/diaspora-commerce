package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.deliveryruns.domain.AdvertorialSource;
import com.afrotransact.deliveryruns.domain.AdvertorialStatus;

/**
 * Staff: location advertorials per pre-order window or delivery run (S263). Reads need {@code delivery-runs:read};
 * writes {@code preorders:manage}. Writes take the version the caller saw ({@code If-Match}; null = any) and answer
 * 412 {@code delivery-runs.version-mismatch} when it changed.
 */
public interface AdvertorialService {

    /** Newest first, with results; null filters match everything. */
    List<AdvertorialView> list(AdvertorialSource source, UUID sourceId, AdvertorialStatus status, int limit);

    AdvertorialView get(UUID id);

    /**
     * Drafts the advertorial of a window or run from its data (town, delivery day, closing time, fee), live at once.
     * Idempotent: asking again re-drafts the words of the generated one (a variant staff edited is left alone).
     * 404 for an unknown source; 409 for one that is over.
     */
    AdvertorialView generate(AdvertorialSource source, UUID sourceId);

    /** A draft written by staff; {@code publish} makes it live. */
    AdvertorialView create(AdvertorialCommand command);

    /** Edits a variant (not an ended one); the words stop following the source. */
    AdvertorialView update(UUID id, AdvertorialCommand command, Long expectedVersion);

    /** Draft or paused to live. */
    AdvertorialView publish(UUID id, Long expectedVersion);

    /** Live to paused. */
    AdvertorialView pause(UUID id, Long expectedVersion);

    /** Ends it for good. */
    AdvertorialView end(UUID id, Long expectedVersion);
}
