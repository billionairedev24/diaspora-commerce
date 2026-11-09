package com.afrotransact.taxreturns.adapter.out.tax;

import java.time.LocalDate;

import com.afrotransact.taxreturns.port.ExemptionCalendar;
import org.springframework.stereotype.Component;

/**
 * Default {@link ExemptionCalendar}: no exemption window is ever open, because the sales tax holiday calendar (dates and
 * value limits) is not built. Items that depend on a window (car seats) are taxed as ordinary sales, the safe direction.
 */
@Component
class NoHolidayCalendar implements ExemptionCalendar {

    @Override
    public boolean windowActive(String categoryCode, LocalDate saleDate) {
        return false;
    }
}
