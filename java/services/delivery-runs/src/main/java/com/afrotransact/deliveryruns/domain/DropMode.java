package com.afrotransact.deliveryruns.domain;

/** How a run hands orders over: at each customer's door, or at one community pickup point with handover codes. */
public enum DropMode {
    DOORSTEP,
    PICKUP_POINT
}
