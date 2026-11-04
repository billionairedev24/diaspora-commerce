package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.NexusRow;
import com.afrotransact.taxreturns.domain.tax.NexusSetting;
import com.afrotransact.taxreturns.domain.tax.StateTaxRule;
import com.afrotransact.taxreturns.port.TaxNexusRepository;
import com.afrotransact.taxreturns.port.TaxRateRepository;
import com.afrotransact.taxreturns.port.TaxTransactionRepository;
import com.afrotransact.taxreturns.port.TaxTransactionRepository.SalesInto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxNexusServiceImpl implements TaxNexusService {

    private static final Pattern JURISDICTION = Pattern.compile("^US-[A-Z]{2}$");
    private static final String CURRENCY = "USD";

    private final TaxNexusRepository settings;
    private final TaxTransactionRepository transactions;
    private final TaxRateRepository rates;
    private final Access access;
    private final Clock clock;

    TaxNexusServiceImpl(TaxNexusRepository settings, TaxTransactionRepository transactions, TaxRateRepository rates,
                        Access access, Clock clock) {
        this.settings = settings;
        this.transactions = transactions;
        this.rates = rates;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NexusRow> tracker(YearMonth through) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return rows(through).stream().sorted(Comparator.comparingInt((NexusRow r) -> r.status().ordinal())
                .thenComparing(Comparator.comparingInt(NexusRow::percent).reversed())
                .thenComparing(NexusRow::jurisdiction)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public NexusRow state(YearMonth through, String jurisdiction) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return rows(through).stream().filter(r -> r.jurisdiction().equals(jurisdiction)).findFirst()
                .orElseGet(() -> row(jurisdiction, jurisdiction, false, new SalesInto(jurisdiction, 0, 0), null));
    }

    @Override
    @Transactional
    public NexusRow update(UpdateNexus c) {
        Actor actor = access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        Map<String, String> errors = new HashMap<>();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (c.jurisdiction() == null || !JURISDICTION.matcher(c.jurisdiction()).matches()) {
            errors.put("jurisdiction", "Use the state jurisdiction, for example US-TX");
        }
        if (c.salesThresholdMinor() < 0) {
            errors.put("salesThresholdMinor", "Can't be negative");
        }
        if (c.transactionsThreshold() != null && c.transactionsThreshold() < 0) {
            errors.put("transactionsThreshold", "Can't be negative");
        }
        if (c.registered() && (c.registeredOn() == null || c.registeredOn().isAfter(today))) {
            errors.put("registeredOn", "Give the date the registration took effect (not in the future)");
        }
        if (c.registered() && (c.registrationNumber() == null || c.registrationNumber().isBlank())) {
            errors.put("registrationNumber", "Give the state's registration or permit number");
        }
        if (!errors.isEmpty()) {
            throw new ReturnRuleViolation("tax.invalid-nexus", "The state's record isn't complete", errors);
        }
        settings.save(new NexusSetting(c.jurisdiction(), c.salesThresholdMinor(), c.transactionsThreshold(),
                c.registered(), c.registered() ? c.registrationNumber().trim() : null,
                c.registered() ? c.registeredOn() : null, c.note() == null || c.note().isBlank() ? null : c.note().trim(),
                actor.subject(), Instant.now(clock)));
        YearMonth through = c.through() == null ? YearMonth.from(today) : c.through();
        return rows(through).stream().filter(r -> r.jurisdiction().equals(c.jurisdiction())).findFirst().orElseThrow();
    }

    private List<NexusRow> rows(YearMonth through) {
        Map<String, SalesInto> sales = new HashMap<>();
        transactions.salesInto(through, WINDOW_MONTHS, CURRENCY).forEach(s -> sales.put(s.jurisdiction(), s));
        Map<String, NexusSetting> saved = new HashMap<>();
        settings.all().forEach(s -> saved.put(s.jurisdiction(), s));
        Map<String, StateTaxRule> states = new HashMap<>();
        rates.states().stream().filter(s -> "US".equals(s.country())).forEach(s -> states.put("US-" + s.state(), s));
        Set<String> keys = new HashSet<>(sales.keySet());
        keys.addAll(saved.keySet());
        keys.removeIf(k -> !JURISDICTION.matcher(k).matches());
        List<NexusRow> out = new ArrayList<>();
        for (String k : keys) {
            StateTaxRule rule = states.get(k);
            out.add(row(k, rule == null ? k : rule.name(), rule != null && rule.facilitator(),
                    sales.getOrDefault(k, new SalesInto(k, 0, 0)), saved.get(k)));
        }
        return out;
    }

    private static NexusRow row(String jurisdiction, String name, boolean facilitator, SalesInto s, NexusSetting set) {
        long threshold = set == null ? DEFAULT_SALES_THRESHOLD_MINOR : set.salesThresholdMinor();
        Integer txns = set == null ? null : set.transactionsThreshold();
        boolean registered = set != null && set.registered();
        int percent = NexusRow.percentOf(s.grossMinor(), threshold, s.orders(), txns);
        return new NexusRow(jurisdiction, name, facilitator, s.grossMinor(), s.orders(), threshold, txns, percent,
                NexusRow.statusOf(registered, percent), registered, set == null ? null : set.registrationNumber(),
                set == null ? null : set.registeredOn(), set == null ? null : set.note(), set != null,
                set == null ? null : set.updatedBy(), set == null ? null : set.updatedAt());
    }
}
