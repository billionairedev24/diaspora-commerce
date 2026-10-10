package com.afrotransact.bookings.application;

/**
 * Background work (scheduled by adapters): expire unpaid holds, expire unanswered requests (releasing the deposit
 * hold) and auto-complete appointments some time after they end (charging the balance).
 */
public interface BookingMaintenanceService {

    int expireHolds();

    int expireRequests();

    int autoComplete();
}
