package com.afrotransact.deliveryruns.port;

import java.time.YearMonth;

/** A count per calendar month (UTC). */
public record MonthlyCount(YearMonth month, long count) {
}
