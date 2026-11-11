package com.afrotransact.seller.application;

/** Scheduled escalation of cases that are overdue or that nobody has taken (see {@link ReviewSettingsService}). */
public interface EscalationService {

    /** Checks every case in staff hands; returns how many were escalated. Safe to run on several replicas at once. */
    int runDue();
}
