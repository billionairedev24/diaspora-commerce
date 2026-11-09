package com.afrotransact.taxreturns.adapter.out.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.taxreturns.domain.tax.DefaultTaxRules;
import com.afrotransact.taxreturns.domain.tax.Jurisdiction;
import com.afrotransact.taxreturns.domain.tax.LocalTaxRate;
import com.afrotransact.taxreturns.domain.tax.StateTaxRule;
import com.afrotransact.taxreturns.domain.tax.TaxAddress;
import com.afrotransact.taxreturns.domain.tax.TaxCategory;
import com.afrotransact.taxreturns.domain.tax.TaxLineInput;
import com.afrotransact.taxreturns.domain.tax.TaxQuote;
import com.afrotransact.taxreturns.domain.tax.TaxRateTable;
import com.afrotransact.taxreturns.port.TaxRateRepository;
import org.junit.jupiter.api.Test;

/** The default resolver is the ZIP table (the documented TX-03 limitation) and every line records its precision. */
class JurisdictionResolverTest {

    private final TaxRateRepository rates = mock(TaxRateRepository.class);

    private RulesTaxCalculator calculator() {
        StateTaxRule tx = new StateTaxRule("US", "TX", "Texas", new BigDecimal("0.0625"), new BigDecimal("0"),
                new BigDecimal("0.0625"), true, true, 0);
        when(rates.table()).thenReturn(new TaxRateTable(List.of(tx), List.of(new LocalTaxRate("TX", "770", "Houston",
                new BigDecimal("0.02"), true))));
        return new RulesTaxCalculator(new ZipTableJurisdictionResolver(rates), new DefaultTaxRules(),
                Clock.systemUTC());
    }

    @Test
    void theZipTableResolverReportsZipPrecisionAndItsSource() {
        calculator();
        Jurisdiction j = new ZipTableJurisdictionResolver(rates).resolve(new TaxAddress("US", "TX", "77002", "Houston"),
                LocalDate.now());

        assertThat(j.addressPrecision()).isEqualTo("ZIP");
        assertThat(j.source()).isEqualTo("zip-table");
        assertThat(j.local()).isPresent();
    }

    @Test
    void everyTaxedLineRecordsZipPrecisionAndTheRateComesFromTheResolver() {
        TaxQuote q = calculator().quote(new TaxAddress("US", "TX", "77002", "Houston"), "USD", List.of(
                new TaxLineInput("1", "AT1", "org", TaxCategory.GENERAL, 1, 10_000, 0, 0)));

        assertThat(q.lines().getFirst().rate()).isEqualByComparingTo("0.0825");
        assertThat(q.lines().getFirst().taxMinor()).isEqualTo(825);
        assertThat(q.lines().getFirst().addressPrecision()).isEqualTo("ZIP");
    }

    @Test
    void theNoHolidayCalendarNeverOpensAWindow() {
        assertThat(new NoHolidayCalendar().windowActive("car-seats", LocalDate.parse("2026-08-07"))).isFalse();
    }
}
