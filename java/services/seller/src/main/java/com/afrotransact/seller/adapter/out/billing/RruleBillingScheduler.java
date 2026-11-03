package com.afrotransact.seller.adapter.out.billing;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.stream.IntStream;

import com.afrotransact.seller.domain.Cadence;
import com.afrotransact.seller.port.BillingScheduler;
import net.fortuna.ical4j.model.Month;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.transform.recurrence.Frequency;

/**
 * ical4j-backed implementation of {@link BillingScheduler}. Billing periods are RFC 5545 recurrences anchored on the
 * subscription's activation date (or trial end). No network calls are made.
 *
 * <p>Cadence mapping:
 * <ul>
 *   <li>MONTHLY  → {@code FREQ=MONTHLY;INTERVAL=1}
 *   <li>QUARTERLY → {@code FREQ=MONTHLY;INTERVAL=3}
 *   <li>ANNUAL   → {@code FREQ=YEARLY;INTERVAL=1}
 * </ul>
 */
public class RruleBillingScheduler implements BillingScheduler {

    @Override
    public Instant periodStart(Instant anchorDate, Cadence cadence, Instant now) {
        // Walk recurrences from anchor; the start of the current period is the last occurrence on or before now.
        Instant candidate = anchorDate.truncatedTo(ChronoUnit.SECONDS);
        if (!candidate.isBefore(now)) {
            return candidate; // now is within the very first period
        }
        ZonedDateTime anchorZdt = candidate.atZone(ZoneOffset.UTC);
        Recur<ZonedDateTime> recur = recur(cadence, anchorZdt);
        ZonedDateTime nowZdt = now.atZone(ZoneOffset.UTC);

        // getDates returns occurrences in the half-open interval (start, end]; we iterate to find the last one ≤ now.
        Instant last = candidate;
        Iterator<ZonedDateTime> it = recur.getDates(anchorZdt, anchorZdt, nowZdt.plusSeconds(1)).iterator();
        while (it.hasNext()) {
            Instant occ = it.next().toInstant();
            if (!occ.isAfter(now)) {
                last = occ;
            }
        }
        return last;
    }

    @Override
    public Instant periodEnd(Instant anchorDate, Cadence cadence, Instant now) {
        Instant start = periodStart(anchorDate, cadence, now);
        return nextPeriodEnd(anchorDate, cadence, start);
    }

    @Override
    public Instant nextPeriodEnd(Instant anchorDate, Cadence cadence, Instant after) {
        ZonedDateTime anchorZdt = anchorDate.truncatedTo(ChronoUnit.SECONDS).atZone(ZoneOffset.UTC);
        Recur<ZonedDateTime> recur = recur(cadence, anchorZdt);
        ZonedDateTime afterZdt = after.truncatedTo(ChronoUnit.SECONDS).atZone(ZoneOffset.UTC);
        // One occurrence strictly after `after` is the start of the next period = end of current one.
        Iterator<ZonedDateTime> it = recur.getDates(anchorZdt, afterZdt, afterZdt.plusYears(3)).iterator();
        while (it.hasNext()) {
            Instant occ = it.next().toInstant();
            if (occ.isAfter(after)) {
                return occ;
            }
        }
        // Fallback (should never happen for standard cadences within 3 years):
        return fallback(cadence, after);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The cadence's recurrence. RFC 5545 skips months that lack the anchor's day (Jan 31 + 1 month would be Mar 31), which
     * would bill two months at once; for anchors on the 29th–31st the rule picks the last valid day of
     * {@code BYMONTHDAY=28..day} instead (Jan 31, Feb 28, Mar 31, Apr 30), so every period is one cadence long and the
     * cycle returns to the anchor day.
     */
    private static Recur<ZonedDateTime> recur(Cadence cadence, ZonedDateTime anchor) {
        Recur.Builder<ZonedDateTime> b = new Recur.Builder<ZonedDateTime>().interval(cadence == Cadence.QUARTERLY ? 3 : 1);
        b.frequency(cadence == Cadence.ANNUAL ? Frequency.YEARLY : Frequency.MONTHLY);
        int day = anchor.getDayOfMonth();
        if (day > 28) {
            if (cadence == Cadence.ANNUAL) {
                b.monthList(new Month(anchor.getMonthValue()));
            }
            b.monthDayList(IntStream.rangeClosed(28, day).boxed().toList());
            b.setPosList(-1);
        }
        return b.build();
    }

    /** Simple arithmetic fallback — adds months/years directly without rrule. */
    private static Instant fallback(Cadence cadence, Instant after) {
        ZonedDateTime zdt = after.atZone(ZoneOffset.UTC);
        return switch (cadence) {
            case MONTHLY -> zdt.plusMonths(1).toInstant();
            case QUARTERLY -> zdt.plusMonths(3).toInstant();
            case ANNUAL -> zdt.plusYears(1).toInstant();
        };
    }
}
