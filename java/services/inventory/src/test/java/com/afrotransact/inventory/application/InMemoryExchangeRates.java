package com.afrotransact.inventory.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.afrotransact.inventory.domain.ExchangeRate;
import com.afrotransact.inventory.domain.ExchangeRateChange;
import com.afrotransact.inventory.port.ExchangeRateRepository;
import com.afrotransact.inventory.port.Page;

/** In-memory rates for unit tests of the use cases. */
final class InMemoryExchangeRates implements ExchangeRateRepository {

    private final Map<String, TreeMap<LocalDate, ExchangeRate>> rates = new TreeMap<>();
    private final List<ExchangeRateChange> audit = new ArrayList<>();

    @Override
    public Optional<ExchangeRate> lockByDay(String currency, LocalDate rateDate) {
        return Optional.ofNullable(rates.getOrDefault(currency, new TreeMap<>()).get(rateDate));
    }

    @Override
    public Optional<ExchangeRate> latestOnOrBefore(String currency, LocalDate day) {
        return Optional.ofNullable(rates.getOrDefault(currency, new TreeMap<>()).floorEntry(day))
                .map(Map.Entry::getValue);
    }

    @Override
    public List<ExchangeRate> latestPerCurrency(LocalDate day) {
        return rates.keySet().stream().map(c -> latestOnOrBefore(c, day)).flatMap(Optional::stream).toList();
    }

    @Override
    public Page<ExchangeRate> search(String currency, int page, int size) {
        List<ExchangeRate> all = rates.values().stream().flatMap(m -> m.values().stream())
                .filter(r -> currency == null || r.currency().equals(currency))
                .sorted(Comparator.comparing(ExchangeRate::rateDate).reversed()).toList();
        return new Page<>(all, all.size(), page, size);
    }

    @Override
    public void insert(ExchangeRate rate) {
        rates.computeIfAbsent(rate.currency(), k -> new TreeMap<>()).put(rate.rateDate(), rate);
    }

    @Override
    public void update(ExchangeRate rate) {
        insert(rate);
    }

    @Override
    public void audit(ExchangeRateChange change) {
        audit.add(change);
    }

    @Override
    public Page<ExchangeRateChange> history(String currency, int page, int size) {
        List<ExchangeRateChange> all = audit.stream().filter(c -> currency == null || c.currency().equals(currency))
                .toList();
        return new Page<>(all, all.size(), page, size);
    }
}
