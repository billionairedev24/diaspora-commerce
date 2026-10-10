package com.afrotransact.taxreturns.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.LocalTaxRate;
import com.afrotransact.taxreturns.domain.tax.StateTaxRule;
import com.afrotransact.taxreturns.domain.tax.TaxCategory;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.TaxCalculator;
import com.afrotransact.taxreturns.port.TaxRateRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxServiceImpl implements TaxService {

    private static final int MAX_LINES = 500;

    private final TaxCalculator calculator;
    private final TaxRateRepository rates;
    private final TaxSettings settings;
    private final Access access;

    TaxServiceImpl(TaxCalculator calculator, TaxRateRepository rates, TaxSettings settings, Access access) {
        this.calculator = calculator;
        this.rates = rates;
        this.settings = settings;
        this.access = access;
    }

    @Override
    @Transactional(readOnly = true)
    public TaxQuote quote(TaxQuoteCommand c) {
        access.staff(TaxReturnsPermissions.TAX_QUOTE);
        Map<String, String> errors = new LinkedHashMap<>();
        if (c.currency() == null || !c.currency().matches("[A-Za-z]{3}")) {
            errors.put("currency", "ISO 4217 currency code");
        }
        if (c.shipTo() == null) {
            errors.put("shipTo", "Ship-to address is required");
        }
        if (c.lines().isEmpty() || c.lines().size() > MAX_LINES) {
            errors.put("lines", "Between 1 and " + MAX_LINES + " lines");
        }
        List<TaxLineInput> inputs = new ArrayList<>();
        for (int i = 0; i < c.lines().size(); i++) {
            TaxQuoteCommand.Line l = c.lines().get(i);
            TaxCategory category = l.taxCategory() != null && !l.taxCategory().isBlank()
                    ? TaxCategory.parse(l.taxCategory()) : settings.categoryFor(l.productType());
            try {
                inputs.add(new TaxLineInput(l.lineId() == null ? String.valueOf(i + 1) : l.lineId(), l.atin(),
                        l.sellerOrgId(), category, l.quantity(), l.unitPriceMinor(), l.discountMinor(),
                        l.shippingMinor()));
            } catch (IllegalArgumentException ex) {
                errors.put("lines[" + i + "]", ex.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new ReturnRuleViolation("tax-returns.invalid-quote", "The tax quote request is invalid", errors);
        }
        return calculator.quote(c.shipTo(), c.currency().toUpperCase(Locale.ROOT), inputs);
    }

    @Override
    @Transactional(readOnly = true)
    public RateTableView rates() {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return new RateTableView(rates.states(), rates.locals());
    }

    @Override
    @Transactional
    public StateTaxRule upsertState(StateTaxRule rule) {
        access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        return rates.upsertState(rule);
    }

    @Override
    @Transactional
    public LocalTaxRate upsertLocal(LocalTaxRate rate) {
        access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        if (rates.state("US", rate.state()).isEmpty()) {
            throw new NotFoundException("tax-returns.state-not-found", "Add the state rule for " + rate.state()
                    + " first");
        }
        return rates.upsertLocal(rate);
    }

    @Override
    @Transactional
    public void deleteLocal(String state, String zipPrefix) {
        access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        if (!rates.deleteLocal(state.toUpperCase(Locale.ROOT), zipPrefix)) {
            throw new NotFoundException("tax-returns.local-rate-not-found", "No local rate for " + state + " "
                    + zipPrefix);
        }
    }
}
