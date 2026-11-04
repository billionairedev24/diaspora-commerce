package com.afrotransact.inventory.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.inventory.domain.CostComponents;
import com.afrotransact.inventory.domain.ExchangeRate;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.LotCost;
import com.afrotransact.inventory.domain.RateSource;
import com.afrotransact.inventory.port.ExchangeRateRepository;
import org.springframework.stereotype.Component;

@Component
class LotCostValuerImpl implements LotCostValuer {

    private final ExchangeRateRepository rates;
    private final InventorySettings settings;

    LotCostValuerImpl(ExchangeRateRepository rates, InventorySettings settings) {
        this.rates = rates;
        this.settings = settings;
    }

    @Override
    public List<LotCost> value(List<ReceiveCommand.Line> lines, LocalDate today) {
        Map<String, String> errors = new LinkedHashMap<>();
        List<LotCost> costs = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            costs.add(valueLine(lines.get(i), today, "lines[" + i + "].", errors));
        }
        if (!errors.isEmpty()) {
            throw new InventoryRuleViolation("inventory.invalid-cost", "Receipt rejected: the cost of some lines "
                    + "cannot be valued in US dollars", errors);
        }
        return costs;
    }

    /** The line's cost, or null after recording why it cannot be valued. */
    private LotCost valueLine(ReceiveCommand.Line line, LocalDate today, String field, Map<String, String> errors) {
        String currency = line.currency();
        if (!settings.acceptsCostCurrency(currency)) {
            errors.put(field + "currency", currency + " is not a currency stock is received in; use one of "
                    + String.join(", ", settings.costCurrencies()));
            return null;
        }
        CostComponents components = line.costComponents();
        if (components != null && components.totalMinor() != line.unitLandedCostMinor()) {
            errors.put(field + "costComponents", "Goods, freight, duty and other must add up to the unit cost ("
                    + line.unitLandedCostMinor() + ")");
            return null;
        }
        if (LotCost.USD.equals(currency)) {
            return valueUsd(line, today, components, field, errors);
        }
        return valueForeign(line, today, components, field, errors);
    }

    private static LotCost valueUsd(ReceiveCommand.Line line, LocalDate today, CostComponents components, String field,
                                    Map<String, String> errors) {
        if (line.fxRateToUsd() != null && line.fxRateToUsd().compareTo(BigDecimal.ONE) != 0) {
            errors.put(field + "fxRateToUsd", "US dollars have rate 1");
            return null;
        }
        if (line.unitCostUsdMinor() != null && line.unitCostUsdMinor() != line.unitLandedCostMinor()) {
            errors.put(field + "unitCostUsdMinor", "Must equal the unit cost for US dollars");
            return null;
        }
        return LotCost.usd(line.unitLandedCostMinor(), today, components);
    }

    private LotCost valueForeign(ReceiveCommand.Line line, LocalDate today, CostComponents components, String field,
                                 Map<String, String> errors) {
        if (line.fxRateToUsd() != null) {
            BigDecimal rate = line.fxRateToUsd();
            if (rate.signum() <= 0) {
                errors.put(field + "fxRateToUsd", "The exchange rate must be greater than 0");
                return null;
            }
            if (rate.stripTrailingZeros().scale() > LotCost.RATE_SCALE) {
                errors.put(field + "fxRateToUsd", "At most " + LotCost.RATE_SCALE + " decimals");
                return null;
            }
            LocalDate day = line.fxRateDate() == null ? today : line.fxRateDate();
            if (day.isAfter(today)) {
                errors.put(field + "fxRateDate", "The rate cannot be for a future day");
                return null;
            }
            BigDecimal tidy = ExchangeRate.tidy(rate);
            if (line.unitCostUsdMinor() != null) {
                if (line.unitCostUsdMinor() < 0) {
                    errors.put(field + "unitCostUsdMinor", "Must be ≥ 0");
                    return null;
                }
                return new LotCost(line.unitLandedCostMinor(), line.currency(), tidy, day, RateSource.ENTERED,
                        line.unitCostUsdMinor(), components);
            }
            return LotCost.valued(line.unitLandedCostMinor(), line.currency(), tidy, day, RateSource.ENTERED,
                    components);
        }
        if (line.unitCostUsdMinor() != null || line.fxRateDate() != null) {
            errors.put(field + "fxRateToUsd", "Enter the rate that goes with the date or the USD value");
            return null;
        }
        Optional<ExchangeRate> suggested = rates.latestOnOrBefore(line.currency(), today);
        if (suggested.isEmpty()) {
            errors.put(field + "fxRateToUsd", "No " + line.currency() + " rate on file: enter the rate to USD");
            return null;
        }
        ExchangeRate row = suggested.get();
        return LotCost.valued(line.unitLandedCostMinor(), line.currency(), row.rateToUsd(), row.rateDate(),
                RateSource.TABLE, components);
    }
}
