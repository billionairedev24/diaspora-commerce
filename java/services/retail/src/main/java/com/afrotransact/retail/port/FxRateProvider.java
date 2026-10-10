package com.afrotransact.retail.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** Exchange rates to capture on POs and invoices: units of {@code currency} per one unit of the base currency. */
public interface FxRateProvider {

    Optional<BigDecimal> unitsPerBase(String currency, LocalDate date);
}
