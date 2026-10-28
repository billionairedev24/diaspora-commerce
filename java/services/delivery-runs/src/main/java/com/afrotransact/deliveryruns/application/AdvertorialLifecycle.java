package com.afrotransact.deliveryruns.application;

import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.PreorderWindow;
import com.afrotransact.deliveryruns.domain.Run;

/**
 * Follows the run and window events this service publishes (S263): an announced or opened run, a published window,
 * drafts its advertorial; an extended run or edited window re-drafts the generated words; a window that closed or a
 * run that was confirmed, driven or completed ends its advertorials; a cancelled window or run ends them as cancelled.
 * Called with the event type in the same transaction as the event, so an advertorial never outlives its source.
 */
public interface AdvertorialLifecycle {

    /** A run event ({@code delivery-runs.run.*}). */
    void onRun(String eventType, Location location, Run run);

    /** A window event ({@code preorder.window.*}). */
    void onWindow(String eventType, PreorderWindow window);
}
