package com.afrotransact.taxreturns.port;

import java.time.LocalDate;

/**
 * Whether a date-bound exemption window (a sales tax holiday) is open for a category on a date. The calendar is not built
 * yet: the default adapter answers false for everything, so such items are taxed as ordinary sales (the safe direction).
 */
public interface ExemptionCalendar {

    boolean windowActive(String categoryCode, LocalDate saleDate);
}
