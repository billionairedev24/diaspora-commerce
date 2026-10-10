package com.afrotransact.orders.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Adds business days (Monday–Friday) to a date for delivery promises. */
public final class BusinessDays {

    private BusinessDays() {
    }

    public static LocalDate add(LocalDate start, int days) {
        LocalDate d = start;
        int left = Math.max(0, days);
        while (left > 0) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                left--;
            }
        }
        return d;
    }
}
