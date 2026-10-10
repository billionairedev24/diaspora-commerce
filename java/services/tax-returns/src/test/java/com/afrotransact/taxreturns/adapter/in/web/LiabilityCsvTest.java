package com.afrotransact.taxreturns.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.List;

import com.afrotransact.taxreturns.application.LiabilityReport;
import com.afrotransact.taxreturns.domain.tax.LiabilityRow;
import org.junit.jupiter.api.Test;

class LiabilityCsvTest {

    @Test
    void writesOneRowPerJurisdictionWithMinorAndDecimalAmounts() {
        String csv = LiabilityCsv.write(new LiabilityReport(YearMonth.of(2026, 10), null, List.of(
                new LiabilityRow("US-NY", "USD", 3, 20_000, 18_000, 1_598, 0, 1_598),
                new LiabilityRow("US-TX", "USD", 2, 15_000, 15_000, 1_238, 536, 702))));
        assertThat(csv.lines().toList()).containsExactly(LiabilityCsv.HEADER,
                "2026-10,,US-NY,USD,3,20000,18000,1598,0,1598,15.98",
                "2026-10,,US-TX,USD,2,15000,15000,1238,536,702,7.02");
    }

    @Test
    void quotesAndNeutralisesFormulas() {
        assertThat(LiabilityCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(LiabilityCsv.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(LiabilityCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(LiabilityCsv.major(-536, "USD")).isEqualTo("-5.36");
        assertThat(LiabilityCsv.major(1_500, "JPY")).isEqualTo("1500");
        assertThat(LiabilityCsv.major(1_500, "???")).isEqualTo("15.00");
    }
}
