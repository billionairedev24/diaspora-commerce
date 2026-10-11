package com.afrotransact.deliveryruns.application;

import java.util.List;
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

    /** Switches the window back on; its publish checks must still pass (422 listing them). */
    WindowView resume(UUID id, Long expectedVersion);

    /**
     * Sets this window's own on/off switch (idempotent). The master switch is never touched: a window is live when the
     * master switch AND its own switch AND one of its areas are on. Switching off stops storefront exposure
     * (resolution, banners, eligibility and the cart's pre-order packages); orders already placed are kept. Switching
     * on re-runs the publish checks. Drafts and delivered windows answer 409. Publishes
     * preorder.window.paused / resumed (with the actor) when the switch flips.
     */
    WindowView setActive(UUID id, boolean active, Long expectedVersion);

    /** {@link #setActive(UUID, boolean, Long)} for several windows, each on its own (at most 100). */
    WindowSwitchResult setActive(List<UUID> ids, boolean active);

    /** Attaches a media-service asset to a slot (verified with media-service; size stored; ratio checked). */
    WindowView setCreative(UUID id, CreativeSlot slot, UUID assetId, Long expectedVersion);

    WindowView removeCreative(UUID id, CreativeSlot slot, Long expectedVersion);
}
