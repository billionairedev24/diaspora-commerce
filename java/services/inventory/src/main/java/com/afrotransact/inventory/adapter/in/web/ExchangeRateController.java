package com.afrotransact.inventory.adapter.in.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.inventory.application.ExchangeRateService;
import com.afrotransact.inventory.domain.ExchangeRate;
import com.afrotransact.inventory.domain.ExchangeRateChange;
import com.afrotransact.inventory.port.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Exchange rates", description = "Customers pay in USD, but stock is sourced and received in the currency "
        + "actually paid (ADR 0005 §7). The currencies stock can be costed in and the staff-managed rates to USD "
        + "that are suggested at receiving; every change is audited with a reason.")
@RestController
class ExchangeRateController {

    private final ExchangeRateService rates;

    ExchangeRateController(ExchangeRateService rates) {
        this.rates = rates;
    }

    @Operation(operationId = "listCostCurrencies", summary = "Currencies stock can be received in (inventory:read)",
            description = "The configured list (`at.inventory.cost-currencies`), USD first.")
    @GetMapping("/v1/currencies")
    List<CostCurrencyResponse> currencies() {
        return rates.currencies().stream().map(CostCurrencyResponse::of).toList();
    }

    @Operation(operationId = "listExchangeRates", summary = "Exchange rates to USD, newest day first (staff, "
            + "inventory:read)")
    @GetMapping("/v1/exchange-rates")
    ExchangeRatePage list(@RequestParam(required = false) String currency,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "50") int size) {
        return ExchangeRatePage.of(rates.list(currency, page, size));
    }

    @Operation(operationId = "currentExchangeRates", summary = "The rate suggested at receiving, per currency (staff, "
            + "inventory:read)",
            description = "For every currency that has one: the latest rate on or before `asOf` (default today, UTC).")
    @GetMapping("/v1/exchange-rates/current")
    List<ExchangeRateResponse> current(@RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return rates.current(asOf).stream().map(ExchangeRateResponse::of).toList();
    }

    @Operation(operationId = "exchangeRateHistory", summary = "Who changed which rate and why, newest first (staff, "
            + "inventory:read)")
    @GetMapping("/v1/exchange-rates/history")
    ExchangeRateChangePage history(@RequestParam(required = false) String currency,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "50") int size) {
        return ExchangeRateChangePage.of(rates.history(currency, page, size));
    }

    @Operation(operationId = "putExchangeRate", summary = "Set the rate of a currency on a day (inventory:adjust)",
            description = "Creates the rate, or replaces it; the reason is required and kept in the audit with the old "
                    + "and new rate. 422 for a currency that is not in the configured list (or USD), a rate that is "
                    + "not positive, a future day or a missing reason.")
    @PutMapping("/v1/exchange-rates/{currency}/{date}")
    ExchangeRateResponse put(@PathVariable String currency,
                             @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                             @Valid @RequestBody SetRateRequest body) {
        return ExchangeRateResponse.of(rates.set(currency, date, body.rateToUsd(), body.note(), body.reason()));
    }

    @Schema(name = "CostCurrency", description = "A currency stock can be received in")
    record CostCurrencyResponse(String code, String name,
                                @Schema(description = "Digits after the decimal point; amounts are in minor units")
                                int minorUnits,
                                @Schema(description = "True for USD, in which customers pay and stock is valued")
                                boolean base) {

        static CostCurrencyResponse of(ExchangeRateService.CostCurrency c) {
            return new CostCurrencyResponse(c.code(), c.name(), c.minorUnits(), c.base());
        }
    }

    @Schema(name = "ExchangeRate", description = "On `rateDate` one unit of `currency` was worth `rateToUsd` US dollars")
    record ExchangeRateResponse(String currency, LocalDate rateDate, BigDecimal rateToUsd, String note,
                                String updatedBy, Instant createdAt, Instant updatedAt, long version) {

        static ExchangeRateResponse of(ExchangeRate r) {
            return new ExchangeRateResponse(r.currency(), r.rateDate(), r.rateToUsd(), r.note(), r.updatedBy(),
                    r.createdAt(), r.updatedAt(), r.version());
        }
    }

    @Schema(name = "ExchangeRatePage")
    record ExchangeRatePage(List<ExchangeRateResponse> items, long total, int page, int size) {

        static ExchangeRatePage of(Page<ExchangeRate> p) {
            return new ExchangeRatePage(p.items().stream().map(ExchangeRateResponse::of).toList(), p.total(),
                    p.page(), p.size());
        }
    }

    @Schema(name = "SetExchangeRateRequest")
    record SetRateRequest(@Schema(description = "US dollars per 1 unit of the currency (> 0, at most 10 decimals)")
                          @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("1000000000")
                          BigDecimal rateToUsd,
                          @Schema(description = "Where the rate comes from, e.g. \"Banxico FIX\"") @Size(max = 200)
                          String note,
                          @Schema(description = "Why the rate is set or changed (3 to 500 characters)")
                          @NotBlank @Size(min = 3, max = 500) String reason) {
    }

    @Schema(name = "ExchangeRateChange", description = "One audited change of a rate")
    record ExchangeRateChangeResponse(long id, Instant occurredAt, String currency, LocalDate rateDate, String action,
                                      @Schema(description = "The rate before an UPDATE; null for a CREATE")
                                      BigDecimal oldRate, BigDecimal newRate, String reason, String actor) {

        static ExchangeRateChangeResponse of(ExchangeRateChange c) {
            return new ExchangeRateChangeResponse(c.id(), c.occurredAt(), c.currency(), c.rateDate(),
                    c.action().name(), c.oldRate(), c.newRate(), c.reason(), c.actor());
        }
    }

    @Schema(name = "ExchangeRateChangePage")
    record ExchangeRateChangePage(List<ExchangeRateChangeResponse> items, long total, int page, int size) {

        static ExchangeRateChangePage of(Page<ExchangeRateChange> p) {
            return new ExchangeRateChangePage(p.items().stream().map(ExchangeRateChangeResponse::of).toList(),
                    p.total(), p.page(), p.size());
        }
    }
}
