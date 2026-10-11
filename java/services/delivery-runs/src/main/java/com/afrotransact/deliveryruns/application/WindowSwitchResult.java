package com.afrotransact.deliveryruns.application;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of switching several windows on or off at once: each window is switched on its own (one failing does not
 * undo the others).
 *
 * @param items one per requested window, in request order
 */
public record WindowSwitchResult(List<Item> items) {

    public WindowSwitchResult {
        items = List.copyOf(items);
    }

    public long count(Outcome o) {
        return items.stream().filter(i -> i.outcome() == o).count();
    }

    /** What happened to one window. */
    public enum Outcome {
        /** Its switch flipped. */
        SWITCHED,
        /** It already was in that state. */
        UNCHANGED,
        /** Not switchable (draft, delivered, unknown) or its publish checks fail. */
        SKIPPED
    }

    /**
     * One window.
     *
     * @param id      requested window
     * @param outcome what happened
     * @param view    the window now (null when unknown)
     * @param code    machine-readable reason when skipped
     * @param reason  why it was skipped
     */
    public record Item(UUID id, Outcome outcome, WindowView view, String code, String reason) {
    }
}
