package com.afrotransact.trustops.application;

/**
 * Escalation path driven by the SLA timers (run every minute by the job):
 * pending listings past their SLA are escalated; claims whose seller window lapsed move to staff review; claims under
 * review past their decision SLA are escalated.
 */
public interface SlaMaintenanceService {

    SlaSweepResult sweep();
}
