package com.afrotransact.inventory.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * What one unit of a lot cost: in the currency actually paid, the exchange rate to US dollars fixed when the lot was
 * received (with its date and source) and the resulting value in US cents. Valuation, COGS and margin use the USD
 * value; the original amount stays on the lot.
 *
 * @param unitMinor    unit landed cost in {@code currency} minor units
 * @param currency     ISO 4217 code of the currency paid
 * @param rateToUsd    US dollars per 1 unit of {@code currency} (1 for USD)
 * @param rateDate     the day the rate is for
 * @param rateSource   where the rate came from
 * @param unitUsdMinor unit landed cost in US cents, fixed at receiving
 * @param components   unit cost by goods, freight, duty and other (adds up to {@code unitMinor})
 */
public record LotCost(long unitMinor, String currency, BigDecimal rateToUsd, LocalDate rateDate, RateSource rateSource,
                      long unitUsdMinor, CostComponents components) {

    /** Largest number of decimals kept in a rate. */
    public static final int RATE_SCALE = 10;
    public static final String USD = "USD";

    public LotCost {
        requireCurrency(currency);
        if (rateToUsd == null || rateToUsd.signum() <= 0) {
            throw new InventoryRuleViolation("inventory.invalid-rate", "The exchange rate must be positive",
                    Map.of("fxRateToUsd", "Must be greater than 0"));
        }
        if (unitMinor < 0 || unitUsdMinor < 0) {
            throw new InventoryRuleViolation("inventory.invalid-request", "Unit cost cannot be negative",
                    Map.of("unitLandedCostMinor", "Must be ≥ 0"));
        }
        if (USD.equals(currency) && rateToUsd.compareTo(BigDecimal.ONE) != 0) {
            throw new InventoryRuleViolation("inventory.invalid-rate", "The rate of the US dollar is 1",
                    Map.of("fxRateToUsd", "US dollars have rate 1"));
        }
        components = components == null ? CostComponents.ofTotal(unitMinor) : components;
        if (components.totalMinor() != unitMinor) {
            throw new InventoryRuleViolation("inventory.cost-components", "Goods, freight, duty and other must add "
                    + "up to the unit cost (" + unitMinor + ")",
                    Map.of("costComponents", "Must add up to the unit cost"));
        }
    }

    /** A cost in US dollars: rate 1, nothing to convert. */
    public static LotCost usd(long unitMinor, LocalDate on) {
        return usd(unitMinor, on, null);
    }

    /** A cost in US dollars with its breakdown. */
    public static LotCost usd(long unitMinor, LocalDate on, CostComponents components) {
        return new LotCost(unitMinor, USD, BigDecimal.ONE, on, RateSource.BASE, unitMinor, components);
    }

    /** A cost valued in US cents at {@code rateToUsd}. */
    public static LotCost valued(long unitMinor, String currency, BigDecimal rateToUsd, LocalDate rateDate,
                                 RateSource source, CostComponents components) {
        requireCurrency(currency);
        long usd = UsdConversion.toUsdMinor(unitMinor, currency, rateToUsd);
        return new LotCost(unitMinor, currency, rateToUsd, rateDate, source, usd, components);
    }

    private static void requireCurrency(String currency) {
        if (!UsdConversion.isCurrency(currency)) {
            throw new InventoryRuleViolation("inventory.invalid-currency", "Unknown currency " + currency,
                    Map.of("currency", "ISO 4217 code"));
        }
    }

    public boolean isUsd() {
        return USD.equals(currency);
    }
}
