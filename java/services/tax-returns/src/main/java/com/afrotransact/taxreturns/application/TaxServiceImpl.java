package com.afrotransact.taxreturns.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.config.TaxReturnsProperties;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.CollectedBy;
import com.afrotransact.taxreturns.domain.tax.LineDecision;
import com.afrotransact.taxreturns.domain.tax.LocalTaxRate;
import com.afrotransact.taxreturns.domain.tax.StateTaxRule;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxCategory;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxLineResult;
import com.afrotransact.taxreturns.domain.tax.TaxProviderId;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.port.TaxCalculator;
import com.afrotransact.taxreturns.port.TaxMethodSource;
import com.afrotransact.taxreturns.port.TaxRateRepository;
import com.afrotransact.taxreturns.port.TaxTelemetry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxServiceImpl implements TaxService {

    private static final int MAX_LINES = 500;

    private final TaxCalculator calculator;
    private final TaxRateRepository rates;
    private final LineDecisionService lineDecisions;
    private final TaxShadowService shadow;
    private final TaxMethodSource methods;
    private final TaxTelemetry telemetry;
    private final TaxReturnsProperties props;
    private final Access access;
    private final Clock clock;

    TaxServiceImpl(TaxCalculator calculator, TaxRateRepository rates, LineDecisionService lineDecisions,
                   TaxShadowService shadow, TaxMethodSource methods, TaxTelemetry telemetry,
                   TaxReturnsProperties props, Access access, Clock clock) {
        this.calculator = calculator;
        this.rates = rates;
        this.lineDecisions = lineDecisions;
        this.shadow = shadow;
        this.methods = methods;
        this.telemetry = telemetry;
        this.props = props;
        this.access = access;
        this.clock = clock;
    }

    /**
     * Not one transaction: the reads and the rare write of a stored decision each commit on their own, so a slow provider
     * call never holds a connection.
     */
    @Override
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
        if (!errors.isEmpty()) {
            throw new ReturnRuleViolation("tax-returns.invalid-quote", "The tax quote request is invalid", errors);
        }
        String currency = c.currency().toUpperCase(Locale.ROOT);
        LocalDate saleDate = c.saleDate() == null
                ? LocalDate.now(clock.withZone(props.tax().classification().zone())) : c.saleDate();
        List<LineDecisionService.Request> toDecide = new ArrayList<>();
        for (int i = 0; i < c.lines().size(); i++) {
            TaxQuoteCommand.Line l = c.lines().get(i);
            if (!explicit(l)) {
                toDecide.add(new LineDecisionService.Request(key(l, i), l.atin(), l.sellerOrgId(), l.sellerBehaviour()));
            }
        }
        Map<String, LineDecision> decisions = toDecide.isEmpty() ? Map.of()
                : lineDecisions.decide(toDecide, c.shipTo(), saleDate);
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < c.lines().size(); i++) {
            TaxQuoteCommand.Line l = c.lines().get(i);
            String key = key(l, i);
            try {
                if (explicit(l)) {
                    telemetry.quoteLine("EXPLICIT", "CATEGORY");
                    items.add(new Item(new TaxLineInput(key, l.atin(), l.sellerOrgId(), TaxCategory.parse(l.taxCategory()),
                            l.quantity(), l.unitPriceMinor(), l.discountMinor(), l.shippingMinor()), null));
                    continue;
                }
                LineDecision d = decisions.get(key);
                TaxLineInput in = new TaxLineInput(key, l.atin(), l.sellerOrgId(), TaxCategory.GENERAL, l.quantity(),
                        l.unitPriceMinor(), l.discountMinor(), l.shippingMinor(), d.internalTaxCode(), null,
                        d.providerCodes(), d.exempt());
                items.add(new Item(in, d));
            } catch (IllegalArgumentException ex) {
                errors.put("lines[" + i + "]", ex.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new ReturnRuleViolation("tax-returns.invalid-quote", "The tax quote request is invalid", errors);
        }
        TaxQuote quote = assemble(c.shipTo(), currency, items);
        compareInShadow(c, currency, items);
        return quote;
    }

    /**
     * Blocked lines carry no figure; EXEMPT lines are priced at zero and never reach the calculator (a provider is not
     * billed for them and the shopper is not charged); the rest go to the calculator in one call. An order of only
     * exempt or blocked lines makes no provider call at all.
     */
    private TaxQuote assemble(TaxAddress shipTo, String currency, List<Item> items) {
        List<TaxLineInput> taxable = items.stream().filter(Item::goesToCalculator).map(Item::input).toList();
        TaxQuote quoted = taxable.isEmpty() ? null : calculator.quote(shipTo, currency, taxable);
        Iterator<TaxLineResult> results = quoted == null ? null : quoted.lines().iterator();
        String jurisdiction = shipTo.stateJurisdiction();
        List<TaxLineResult> lines = new ArrayList<>(items.size());
        for (Item item : items) {
            TaxLineInput in = item.input();
            LineDecision d = item.decision();
            if (item.goesToCalculator()) {
                TaxLineResult r = results.next();
                lines.add(d == null ? r : r.withDecision(d));
            } else if (d.isBlocked()) {
                lines.add(TaxLineResult.blocked(in, jurisdiction, d));
            } else {
                lines.add(new TaxLineResult(in.lineId(), in.sellerOrgId(), in.category(), 0L, BigDecimal.ZERO, 0L,
                        jurisdiction, null, CollectedBy.NOT_COLLECTED, 0L, in.taxCode()).withDecision(d)
                        .withAddressPrecision(null));
            }
        }
        if (quoted == null) {
            return new TaxQuote(currency, jurisdiction, CollectedBy.NOT_COLLECTED, lines, "EXEMPT");
        }
        return new TaxQuote(currency, quoted.jurisdiction(), quoted.collectedBy(), lines, quoted.provider(),
                quoted.fallbackLines());
    }

    /** SHADOW mode: asks the shadow provider about the same lines in the background; never changes this quote. */
    private void compareInShadow(TaxQuoteCommand c, String currency, List<Item> items) {
        Optional<TaxProviderId> provider = methods.shadowProvider();
        boolean real = c.reference() != null && !c.reference().isBlank();
        if (provider.isEmpty() || !(real || props.tax().shadow().cartQuotes())) {
            return;
        }
        List<TaxShadowService.Line> lines = items.stream().filter(i -> i.decision() != null)
                .map(i -> new TaxShadowService.Line(i.input(), i.decision())).toList();
        if (!lines.isEmpty()) {
            shadow.compare(new TaxShadowService.Compare(provider.get(), c.shipTo(), currency, c.reference(), lines));
        }
    }

    private static boolean explicit(TaxQuoteCommand.Line l) {
        return l.taxCategory() != null && !l.taxCategory().isBlank();
    }

    private static String key(TaxQuoteCommand.Line l, int index) {
        return l.lineId() == null ? String.valueOf(index + 1) : l.lineId();
    }

    /**
     * @param input    the line
     * @param decision the stored decision, null for a line whose caller chose the category
     */
    private record Item(TaxLineInput input, LineDecision decision) {

        boolean goesToCalculator() {
            return decision == null || decision.taxable();
        }
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

    @Override
    @Transactional
    public TaxRatesImportResult importCsv(String csv) {
        access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        TaxRatesCsvImport.Parsed parsed = TaxRatesCsvImport.parse(csv);
        for (StateTaxRule s : parsed.states()) {
            rates.upsertState(s);
        }
        for (LocalTaxRate l : parsed.locals()) {
            if (rates.state("US", l.state()).isEmpty()) {
                throw new NotFoundException("tax-returns.state-not-found",
                        "Add a STATE row for " + l.state() + " before local ZIP " + l.zipPrefix());
            }
            rates.upsertLocal(l);
        }
        return new TaxRatesImportResult(parsed.states().size(), parsed.locals().size(), parsed.warnings());
    }
}
