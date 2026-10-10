package com.afrotransact.taxreturns.domain.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rules engine over the configured {@link TaxRateTable}.
 *
 * <p>Per line: {@code rate = stateRate(category) + localRate(category)}; the taxable base is
 * {@code unitPrice × quantity − discount}, plus the line's shipping when the state taxes shipping and the item is
 * taxable. {@code tax = base × rate} computed with {@link BigDecimal} and rounded <b>half up to the minor unit once per
 * line</b> (never per unit, never per invoice), so an order's tax is the sum of its line taxes and refunds can reverse
 * a line exactly. Lines shipped outside the US, to a state without a rule, or to a state where the marketplace is not
 * the facilitator get no tax ({@link CollectedBy#NOT_COLLECTED}).
 */
public final class DefaultTaxRules implements TaxRules {

    public static final String PROVIDER = "rules";

    @Override
    public TaxQuote quote(TaxRateTable table, TaxAddress shipTo, String currency, List<TaxLineInput> lines) {
        Optional<StateTaxRule> state = "US".equals(shipTo.country()) ? table.state(shipTo) : Optional.empty();
        boolean collects = state.map(StateTaxRule::facilitator).orElse(false);
        Optional<LocalTaxRate> local = collects ? table.local(shipTo) : Optional.empty();
        String jurisdiction = shipTo.stateJurisdiction();
        String localJurisdiction = local.map(LocalTaxRate::jurisdiction).orElse(null);
        CollectedBy by = collects ? CollectedBy.MARKETPLACE : CollectedBy.NOT_COLLECTED;
        List<TaxLineResult> out = new ArrayList<>(lines.size());
        for (TaxLineInput line : lines) {
            if (!collects) {
                out.add(new TaxLineResult(line.lineId(), line.sellerOrgId(), line.category(), 0L, BigDecimal.ZERO,
                        0L, jurisdiction, null, by));
                continue;
            }
            StateTaxRule rule = state.orElseThrow();
            BigDecimal rate = rule.rateFor(line.category())
                    .add(local.map(l -> l.rateFor(line.category())).orElse(BigDecimal.ZERO));
            long taxable = line.taxableItemsMinor();
            if (rule.shippingTaxable() && rate.signum() > 0) {
                taxable = Math.addExact(taxable, line.shippingMinor());
            }
            long tax = rate.signum() == 0 ? 0L : taxOf(taxable, rate);
            out.add(new TaxLineResult(line.lineId(), line.sellerOrgId(), line.category(), rate.signum() == 0 ? 0L
                    : taxable, rate.stripTrailingZeros(), tax, jurisdiction, localJurisdiction, by));
        }
        return new TaxQuote(currency, jurisdiction, by, out, PROVIDER);
    }

    /** {@code amount × rate}, rounded half up to a whole minor unit. */
    public static long taxOf(long amountMinor, BigDecimal rate) {
        return BigDecimal.valueOf(amountMinor).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
