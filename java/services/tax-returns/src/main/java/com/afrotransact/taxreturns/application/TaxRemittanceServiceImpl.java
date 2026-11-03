package com.afrotransact.taxreturns.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import com.afrotransact.taxreturns.domain.returns.ReturnRuleViolation;
import com.afrotransact.taxreturns.domain.tax.FilingObligation;
import com.afrotransact.taxreturns.domain.tax.LiabilityRow;
import com.afrotransact.taxreturns.domain.tax.TaxRemittance;
import com.afrotransact.taxreturns.port.TaxRemittanceRepository;
import com.afrotransact.taxreturns.port.TaxTransactionRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxRemittanceServiceImpl implements TaxRemittanceService {

    private static final Pattern JURISDICTION = Pattern.compile("^[A-Z]{2}-[A-Z0-9]{2,3}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final int MIN_REASON = 10;
    private static final int MAX_MONTHS = 36;

    private final TaxRemittanceRepository remittances;
    private final TaxTransactionRepository transactions;
    private final Access access;
    private final Clock clock;

    TaxRemittanceServiceImpl(TaxRemittanceRepository remittances, TaxTransactionRepository transactions,
                             Access access, Clock clock) {
        this.remittances = remittances;
        this.transactions = transactions;
        this.access = access;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FilingObligation> obligations(YearMonth through, int months) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        if (months < 1 || months > MAX_MONTHS) {
            throw new ReturnRuleViolation("tax.invalid-range", "Ask for 1 to " + MAX_MONTHS + " months",
                    Map.of("months", "Between 1 and " + MAX_MONTHS));
        }
        YearMonth from = through.minusMonths(months - 1L);
        Map<YearMonth, List<TaxRemittance>> byMonth = new HashMap<>();
        for (TaxRemittance r : remittances.between(from, through, false)) {
            byMonth.computeIfAbsent(r.month(), m -> new ArrayList<>()).add(r);
        }
        List<FilingObligation> out = new ArrayList<>();
        for (YearMonth m = through; !m.isBefore(from); m = m.minusMonths(1)) {
            out.addAll(month(m, byMonth.getOrDefault(m, List.of())));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public FilingObligation obligation(YearMonth month, String jurisdiction, String currency) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return month(month, remittances.between(month, month, false)).stream()
                .filter(o -> o.jurisdiction().equals(jurisdiction) && o.currency().equals(currency)).findFirst()
                .orElse(FilingObligation.of(month, jurisdiction, currency, 0, null));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxRemittance> history(YearMonth month) {
        access.staff(TaxReturnsPermissions.TAX_READ);
        return remittances.between(month, month, true);
    }

    @Override
    @Transactional
    public TaxRemittance record(RecordRemittance c) {
        Actor actor = access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        String currency = c.currency() == null ? "USD" : c.currency();
        validate(c, currency);
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        long due = transactions.liability(c.month(), null).stream()
                .filter(r -> r.jurisdiction().equals(c.jurisdiction()) && r.currency().equals(currency))
                .mapToLong(LiabilityRow::netTaxMinor).sum();
        TaxRemittance r = new TaxRemittance(UUID.randomUUID(), c.jurisdiction(), c.month(), currency, due,
                c.paidMinor(), c.filedOn(), c.paidOn(), blankToNull(c.confirmationRef()), blankToNull(c.paymentRef()),
                blankToNull(c.note()), actor.subject(), Instant.now(clock), null, null, null);
        if (c.month().isAfter(YearMonth.from(today))) {
            throw new ReturnRuleViolation("tax.future-period", "That month hasn't started yet", Map.of("month", "In the future"));
        }
        try {
            remittances.insert(r);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("tax.remittance-exists", "A remittance for " + c.jurisdiction() + " "
                    + c.month() + " is already recorded; void it first if it was a mistake");
        }
        return r;
    }

    @Override
    @Transactional
    public TaxRemittance voidRemittance(UUID id, String reason) {
        Actor actor = access.staff(TaxReturnsPermissions.TAX_CONFIGURE);
        if (reason == null || reason.trim().length() < MIN_REASON) {
            throw new ReturnRuleViolation("tax.reason-required",
                    "Say why the record is withdrawn (at least " + MIN_REASON + " characters)", Map.of("reason", "Required"));
        }
        TaxRemittance existing = remittances.find(id)
                .orElseThrow(() -> new NotFoundException("tax.remittance-not-found", "No such remittance"));
        if (!existing.active()) {
            throw new ConflictException("tax.remittance-voided", "That remittance was already voided");
        }
        remittances.markVoided(id, actor.subject(), Instant.now(clock), reason.trim());
        return remittances.find(id).orElseThrow();
    }

    private void validate(RecordRemittance c, String currency) {
        Map<String, String> errors = new HashMap<>();
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (c.month() == null) {
            errors.put("month", "Required");
        }
        if (c.jurisdiction() == null || !JURISDICTION.matcher(c.jurisdiction()).matches()) {
            errors.put("jurisdiction", "Use the state jurisdiction, for example US-TX");
        }
        if (!CURRENCY.matcher(currency).matches()) {
            errors.put("currency", "A 3-letter currency");
        }
        if (c.paidMinor() < 0) {
            errors.put("paidMinor", "Can't be negative");
        }
        if (c.filedOn() == null || c.filedOn().isAfter(today)) {
            errors.put("filedOn", "Give the date the return was filed (not in the future)");
        }
        if (c.paidOn() == null || c.paidOn().isAfter(today)) {
            errors.put("paidOn", "Give the date the payment was made (not in the future)");
        }
        if (!errors.isEmpty()) {
            throw new ReturnRuleViolation("tax.invalid-remittance", "The remittance isn't complete", errors);
        }
    }

    /** Every jurisdiction with liability or a remittance in the month, sorted by jurisdiction. */
    private List<FilingObligation> month(YearMonth m, List<TaxRemittance> active) {
        Map<String, FilingObligation> byKey = new HashMap<>();
        Map<String, Long> due = new HashMap<>();
        for (LiabilityRow row : transactions.liability(m, null)) {
            due.merge(key(row.jurisdiction(), row.currency()), row.netTaxMinor(), Long::sum);
        }
        Map<String, TaxRemittance> filed = new HashMap<>();
        active.forEach(r -> filed.put(key(r.jurisdiction(), r.currency()), r));
        List<String> keys = new ArrayList<>(due.keySet());
        filed.keySet().stream().filter(k -> !due.containsKey(k)).forEach(keys::add);
        for (String k : keys) {
            String[] parts = k.split("\\|");
            byKey.put(k, FilingObligation.of(m, parts[0], parts[1], due.getOrDefault(k, 0L), filed.get(k)));
        }
        return byKey.values().stream().sorted(Comparator.comparing(FilingObligation::jurisdiction)
                .thenComparing(FilingObligation::currency)).toList();
    }

    private static String key(String jurisdiction, String currency) {
        return jurisdiction + "|" + currency;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
