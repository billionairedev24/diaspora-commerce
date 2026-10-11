package com.afrotransact.deliveryruns.domain;

import java.time.Instant;

/**
 * Whether a published window is live on the storefront, and if not which switch holds it: the effective state is
 * master switch AND window switch AND at least one target area switched on. The window's own switch is never rewritten
 * by the master switch; turning the master back on restores every window to its own state.
 */
public enum WindowSwitch {

    /** Every switch is on: placements, banners, eligibility and the cart's pre-order packages are live. */
    ON,

    /** The window itself is switched off (paused); the other windows are unaffected. */
    PAUSED,

    /** The window is switched on but the platform master switch is off (the global kill switch). */
    GLOBAL_OFF,

    /** The window is switched on but every one of its target areas is switched off. */
    AREA_OFF,

    /** A draft or a delivered window: nothing to switch. */
    NOT_LIVE;

    /** The window shows on the storefront (subject to its dates). */
    public boolean live() {
        return this == ON;
    }

    /**
     * {@code master AND window AND area}. The window's own switch is checked first, so a paused window reads
     * {@link #PAUSED} whatever the master switch says.
     */
    public static WindowSwitch of(PreorderWindow w, Coverage coverage, Instant now) {
        if (!w.switchable(now)) {
            return NOT_LIVE;
        }
        if (!w.active()) {
            return PAUSED;
        }
        if (!coverage.settings().enabled()) {
            return GLOBAL_OFF;
        }
        if (coverage.allTargetsOff(w.details().targets())) {
            return AREA_OFF;
        }
        return ON;
    }
}
