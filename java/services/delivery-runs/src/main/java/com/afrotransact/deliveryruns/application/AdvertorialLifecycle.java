package com.afrotransact.deliveryruns.application;

import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Run;

/**
 * Follows the run and window events this service publishes (S263): an announced or opened run, a published window,
 * drafts its advertorial; an extended run or edited window re-drafts the generated words; a window that closed or a
 * run that was confirmed, driven or completed ends its advertorials; a cancelled window or run ends them as cancelled.
 * An extended run or a resumed window brings back the advertorials the sweeper had ended.
 * Called with the event type in the same transaction as the event, so an advertorial never outlives its source.
 */
public interface AdvertorialLifecycle {

    /**
     * The time-driven part, run on every tick of the run schedule job (S263): ends the LIVE advertorials whose run is
     * past its cutoff, cancelled, confirmed, driven or completed, or whose window is closed, delivered, awaiting a
     * decision, cancelled or paused, with reason {@code SOURCE_CLOSED}; and forgets old view / click rate-limit rows.
     * Idempotent: an ended advertorial is not touched again, and a failing one is retried on the next tick. A run
     * that is extended or a window that resumes reopens what the sweeper ended (see {@link #onRun}).
     *
     * @param limit most LIVE advertorials to look at in one call
     * @return how many advertorials this call ended
     */
    int sweep(int limit);

    /** A run event ({@code delivery-runs.run.*}). */
    void onRun(String eventType, Location location, Run run);

    /** A window event ({@code preorder.window.*}). */
    void onWindow(String eventType, PreorderWindow window);
}
