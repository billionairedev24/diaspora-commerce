package com.afrotransact.inventory.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.afrotransact.inventory.domain.ExchangeRate;
import com.afrotransact.inventory.domain.ExchangeRateChange;
import com.afrotransact.inventory.domain.InventoryRuleViolation;
import com.afrotransact.inventory.domain.LotCost;
import com.afrotransact.inventory.domain.RateAction;
import com.afrotransact.inventory.port.ExchangeRateRepository;
import com.afrotransact.inventory.port.Page;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ExchangeRateServiceImpl implements ExchangeRateService {

    static final int MAX_REASON = 500;
    static final int MIN_REASON = 3;

    private final ExchangeRateRepository rates;
    private final InventoryAccess access;
    private final InventorySettings settings;
    private final Clock clock;

    ExchangeRateServiceImpl(ExchangeRateRepository rates, InventoryAccess access, InventorySettings settings,
                            Clock clock) {
        this.rates = rates;
        this.access = access;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CostCurrency> currencies() {
        access.readScope();
        return settings.costCurrencies().stream().map(ExchangeRateServiceImpl::describe).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExchangeRate> list(String currency, int page, int size) {
        access.staffReader();
        return rates.search(filter(currency), Math.max(page, 0), Math.clamp(size, 1, 200));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExchangeRate> current(LocalDate asOf) {
        access.staffReader();
        return rates.latestPerCurrency(asOf == null ? today() : asOf);
    }

    @Override
    @Transactional
    public ExchangeRate set(String currency, LocalDate rateDate, BigDecimal rateToUsd, String note, String reason) {
        Actor actor = access.require(InventoryPermissions.ADJUST);
        String code = acceptedCurrency(currency);
        LocalDate day = requireDay(rateDate);
        ExchangeRate.requireUsable(rateToUsd);
        String why = requireReason(reason);
        BigDecimal rate = ExchangeRate.tidy(rateToUsd);
        String text = note == null || note.isBlank() ? null : note.trim();
        Instant now = clock.instant();
        Optional<ExchangeRate> existing = rates.lockByDay(code, day);
        if (existing.isPresent()) {
            ExchangeRate before = existing.get();
            if (before.rateToUsd().compareTo(rate) == 0 && Objects.equals(before.note(), text)) {
                return before;
            }
            ExchangeRate after = new ExchangeRate(code, day, rate, text, actor.subject(), before.createdAt(), now,
                    before.version());
            rates.update(after);
            rates.audit(new ExchangeRateChange(0, now, code, day, RateAction.UPDATE, before.rateToUsd(), rate, why,
                    actor.subject()));
            return rates.lockByDay(code, day).orElseThrow();
        }
        ExchangeRate created = new ExchangeRate(code, day, rate, text, actor.subject(), now, now, 0);
        try {
            rates.insert(created);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("inventory.concurrent-update", "The rate of " + code + " on " + day
                    + " was set at the same time; reload and try again");
        }
        rates.audit(new ExchangeRateChange(0, now, code, day, RateAction.CREATE, null, rate, why, actor.subject()));
        return created;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ExchangeRateChange> history(String currency, int page, int size) {
        access.staffReader();
        return rates.history(filter(currency), Math.max(page, 0), Math.clamp(size, 1, 200));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private LocalDate requireDay(LocalDate day) {
        if (day == null) {
            throw new InventoryRuleViolation("inventory.invalid-request", "A rate is for a day",
                    Map.of("rateDate", "Required"));
        }
        // One day of slack: a receiver ahead of UTC may enter tomorrow's rate in the morning.
        if (day.isAfter(today().plusDays(1))) {
            throw new InventoryRuleViolation("inventory.invalid-request", "A rate cannot be for a future day",
                    Map.of("rateDate", "Not after today"));
        }
        return day;
    }

    private String acceptedCurrency(String currency) {
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (LotCost.USD.equals(code) || !settings.acceptsCostCurrency(code)) {
            throw new InventoryRuleViolation("inventory.invalid-currency", LotCost.USD.equals(code)
                    ? "The US dollar is the base currency and has no rate"
                    : code + " is not a currency stock is received in",
                    Map.of("currency", "One of " + String.join(", ", settings.costCurrencies().stream()
                            .filter(c -> !LotCost.USD.equals(c)).toList())));
        }
        return code;
    }

    private static String requireReason(String reason) {
        String why = reason == null ? "" : reason.trim();
        if (why.length() < MIN_REASON || why.length() > MAX_REASON) {
            throw new InventoryRuleViolation("inventory.reason-required", "Say why the rate is set or changed",
                    Map.of("reason", MIN_REASON + " to " + MAX_REASON + " characters"));
        }
        return why;
    }

    private static String filter(String currency) {
        return currency == null || currency.isBlank() ? null : currency.trim().toUpperCase(Locale.ROOT);
    }

    private static CostCurrency describe(String code) {
        Currency c = Currency.getInstance(code);
        return new CostCurrency(code, c.getDisplayName(Locale.ENGLISH), Math.max(0, c.getDefaultFractionDigits()),
                LotCost.USD.equals(code));
    }
}
