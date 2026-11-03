package com.afrotransact.seller.domain;

/**
 * Who sees a plan: everyone ({@code PUBLIC}), nobody ({@code HIDDEN}: subscribers keep it, staff can still migrate
 * them) or only the organisations staff invited ({@code INVITE_ONLY}).
 */
public enum PlanVisibility {
    PUBLIC,
    HIDDEN,
    INVITE_ONLY
}
