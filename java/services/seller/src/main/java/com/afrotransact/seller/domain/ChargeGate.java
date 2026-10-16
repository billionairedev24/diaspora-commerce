package com.afrotransact.seller.domain;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A condition staff put on an organisation's plan charges, with a reason:
 * <ul>
 *   <li>{@link Mode#START_AFTER}: no charge until the seller reaches {@code threshold} of {@code metric} (sales since
 *       {@code since}, or live listings); once reached, billing starts normally and stays on;</li>
 *   <li>{@link Mode#WAIVE_WHILE}: each period is free while the seller keeps at least {@code threshold} of
 *       {@code metric} (for example 20 live listings);</li>
 *   <li>{@link Mode#WAIVE_UNTIL}: every period is free until {@code until}.</li>
 * </ul>
 * A gated period still gets a $0 invoice that says why, so the seller sees it on their Billing page.
 */
public record ChargeGate(Mode mode, RewardMetric metric, Long threshold, Instant since, Instant until,
                         String reasonCode, String note, String setBy, Instant setAt, Instant metAt) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    public enum Mode { START_AFTER, WAIVE_WHILE, WAIVE_UNTIL }

    /**
     * What the gate says about a period starting now.
     *
     * @param charge   whether the plan fee is charged
     * @param reason   why not (shown on the $0 invoice); null when charged
     * @param nowMet   a START_AFTER gate reached its threshold with this evaluation
     * @param expired  a WAIVE_UNTIL gate has run out (clear it)
     */
    public record Decision(boolean charge, String reason, boolean nowMet, boolean expired) {
    }

    /** Decides a period starting at {@code now}; {@code value} is the metric now (ignored by WAIVE_UNTIL). */
    public Decision decide(Instant now, long value) {
        return switch (mode) {
            case START_AFTER -> {
                if (metAt != null) {
                    yield new Decision(true, null, false, false);
                }
                if (value >= threshold) {
                    yield new Decision(true, null, true, false);
                }
                yield new Decision(false, "Charges start once you reach " + metric.format(threshold) + " "
                        + metric.noun() + " (you're at " + metric.format(value) + ")", false, false);
            }
            case WAIVE_WHILE -> value >= threshold
                    ? new Decision(false, "Waived while you keep " + metric.format(threshold) + " " + metric.noun()
                    + " (you have " + metric.format(value) + ")", false, false)
                    : new Decision(true, null, false, false);
            case WAIVE_UNTIL -> until != null && now.isBefore(until)
                    ? new Decision(false, "Free until " + DAY.format(until.atZone(ZoneOffset.UTC)), false, false)
                    : new Decision(true, null, false, true);
        };
    }

    /** Whether this gate reads a metric to decide. */
    public boolean needsMetric() {
        return mode != Mode.WAIVE_UNTIL;
    }
}
