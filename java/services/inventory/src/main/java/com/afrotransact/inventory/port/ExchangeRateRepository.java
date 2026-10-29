package com.afrotransact.inventory.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.afrotransact.inventory.domain.ExchangeRate;
import com.afrotransact.inventory.domain.ExchangeRateChange;

/** Staff-managed exchange rates to USD by currency and day, and their append-only audit. */
public interface ExchangeRateRepository {

    /** The rate of a currency on exactly one day, locked for update inside a transaction. */
    Optional<ExchangeRate> lockByDay(String currency, LocalDate rateDate);

    /** The latest rate of a currency on or before {@code day}: the default suggestion at receiving. */
    Optional<ExchangeRate> latestOnOrBefore(String currency, LocalDate day);

    /** The latest rate on or before {@code day} of every currency that has one, by currency. */
    List<ExchangeRate> latestPerCurrency(LocalDate day);

    /** Rates newest day first, for one currency or all. */
    Page<ExchangeRate> search(String currency, int page, int size);

    void insert(ExchangeRate rate);

    void update(ExchangeRate rate);

    /** Appends an audit row; rows are never changed or removed. */
    void audit(ExchangeRateChange change);

    /** Audit rows newest first, for one currency or all. */
    Page<ExchangeRateChange> history(String currency, int page, int size);
}
