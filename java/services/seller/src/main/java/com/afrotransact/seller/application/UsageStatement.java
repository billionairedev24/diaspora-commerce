package com.afrotransact.seller.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.seller.domain.UsageMeter;

/**
 * An organisation's metered usage for a period (S206): per meter the quantity, what Stripe Billing has received, the
 * estimated amount, and the daily breakdown. The invoice is authoritative; this is the running statement.
 *
 * @param from                inclusive first UTC day (the subscription period start by default)
 * @param to                  inclusive last UTC day
 * @param estimatedTotalCents sum of the meters' estimated amounts
 */
public record UsageStatement(String orgId, LocalDate from, LocalDate to, String currency, List<MeterLine> meters,
                             long estimatedTotalCents) {

    public UsageStatement {
        meters = List.copyOf(meters);
    }

    /**
     * One meter.
     *
     * @param quantity             usage in display units ({@link UsageMeter#displayUnit()})
     * @param reportedQuantity     what Stripe Billing has received, in display units
     * @param unitPriceMicros      estimated price of one display unit in micro-dollars
     * @param estimatedAmountCents estimated amount of {@code quantity}
     */
    public record MeterLine(UsageMeter meter, BigDecimal quantity, BigDecimal reportedQuantity, long unitPriceMicros,
                            long estimatedAmountCents, List<DayLine> days) {

        public MeterLine {
            days = List.copyOf(days);
        }
    }

    /**
     * One day of one meter.
     *
     * @param status REPORTED (Stripe has it all), PENDING (not yet sent, e.g. no Stripe customer yet or retrying),
     *               CORRECTION (usage dropped after it was billed; finance issues a credit)
     * @param error  why it is pending, when known
     */
    public record DayLine(LocalDate day, BigDecimal quantity, BigDecimal reportedQuantity, String status,
                          String error) {
    }
}
