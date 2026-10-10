package com.afrotransact.content.domain.ads;

/** Why a campaign is paused. */
public enum PauseReason {
    /** The seller or staff paused it. */
    MANUAL,
    /** Its total budget is spent; it resumes only after the budget is raised. */
    BUDGET_EXHAUSTED
}
