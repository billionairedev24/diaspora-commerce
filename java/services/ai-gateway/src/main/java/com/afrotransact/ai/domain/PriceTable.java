package com.afrotransact.ai.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Model prices in US dollars per million tokens (from configuration; OpenRouter publishes them per model).
 * Costs are estimated in micro-dollars (1 USD = 1,000,000) so ledger arithmetic stays integral.
 *
 * @param prices       price per model id
 * @param defaultPrice used for models missing from the table (deliberately pessimistic)
 */
public record PriceTable(Map<String, ModelPrice> prices, ModelPrice defaultPrice) {

    private static final BigDecimal MICROS_PER_USD = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal TOKENS_PER_UNIT = BigDecimal.valueOf(1_000_000);

    public PriceTable {
        prices = Map.copyOf(prices);
        if (defaultPrice == null) {
            throw new IllegalArgumentException("defaultPrice is required");
        }
    }

    public ModelPrice priceOf(String model) {
        return prices.getOrDefault(model, defaultPrice);
    }

    /** Estimated cost of a call in micro-dollars, rounded up so budgets are never under-counted. */
    public long costMicros(String model, TokenUsage usage) {
        ModelPrice p = priceOf(model);
        BigDecimal usd = p.inputPerMillion().multiply(BigDecimal.valueOf(usage.promptTokens()))
                .add(p.outputPerMillion().multiply(BigDecimal.valueOf(usage.completionTokens())))
                .divide(TOKENS_PER_UNIT, 12, RoundingMode.HALF_UP);
        return usd.multiply(MICROS_PER_USD).setScale(0, RoundingMode.CEILING).longValueExact();
    }

    /** The cheapest of {@code models} by blended (input + output) price; empty for an empty list. */
    public Optional<String> cheapest(List<String> models) {
        return models.stream().min(Comparator.comparing((String m) -> priceOf(m).blended())
                .thenComparing(models::indexOf));
    }

    /**
     * Price per million tokens.
     *
     * @param inputPerMillion  USD per million prompt tokens
     * @param outputPerMillion USD per million completion tokens
     */
    public record ModelPrice(BigDecimal inputPerMillion, BigDecimal outputPerMillion) {

        public ModelPrice {
            if (inputPerMillion == null || outputPerMillion == null || inputPerMillion.signum() < 0
                    || outputPerMillion.signum() < 0) {
                throw new IllegalArgumentException("prices must be present and non-negative");
            }
        }

        public BigDecimal blended() {
            return inputPerMillion.add(outputPerMillion);
        }
    }
}
