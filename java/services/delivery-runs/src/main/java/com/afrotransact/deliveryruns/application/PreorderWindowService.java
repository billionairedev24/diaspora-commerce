package com.afrotransact.deliveryruns.application;

import java.util.UUID;

import com.afrotransact.deliveryruns.domain.CreativeSlot;

/**
 * Staff: pre-order windows (S270). Reads need {@code delivery-runs:read}; writes {@code preorders:manage}. Writes take
 * the version the caller edited ({@code If-Match}; null = any) and answer 412 {@code delivery-runs.version-mismatch}
 * when the window changed since. Saving a window adds its targets to the coverage areas (switched on) when missing.
 */
public interface PreorderWindowService {

    /**
     * The staff list.
     *
     * @param status tab ({@code all}, {@code open}, {@code upcoming}, {@code closed}, {@code draft}) or a status
     * @param q      search in name, code and areas
     */
    WindowPage list(String status, String q, int page, int size);

    /** A new draft. */
    WindowView create(WindowCommand command);

    WindowView get(UUID id);

    WindowView update(UUID id, WindowCommand command, Long expectedVersion);

    /** All publish checks must pass (422 {@code delivery-runs.preorder-checks-failed} listing them). */
    WindowView publish(UUID id, Long expectedVersion);

    WindowView pause(UUID id, Long expectedVersion);

    WindowView resume(UUID id, Long expectedVersion);

    /** Attaches a media-service asset to a slot (verified with media-service; size stored; ratio checked). */
    WindowView setCreative(UUID id, CreativeSlot slot, UUID assetId, Long expectedVersion);

    WindowView removeCreative(UUID id, CreativeSlot slot, Long expectedVersion);
}
