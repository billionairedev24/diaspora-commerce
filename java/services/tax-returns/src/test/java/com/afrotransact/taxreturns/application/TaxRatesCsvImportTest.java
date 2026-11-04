package com.afrotransact.taxreturns.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class TaxRatesCsvImportTest {

    private static final String SAMPLE = """
            kind,country,state,name,zipPrefix,generalRate,groceryRate,preparedFoodRate,shippingTaxable,facilitator,localRate,appliesToGrocery
            STATE,US,TX,Texas,,0.0625,0,0.0625,true,true,,
            LOCAL,US,TX,Houston area,770,,,,,,0.02,false
            """;

    @Test
    void parsesTexasPilotRows() {
        TaxRatesCsvImport.Parsed p = TaxRatesCsvImport.parse(SAMPLE);
        assertThat(p.states()).hasSize(1);
        assertThat(p.states().getFirst().generalRate()).isEqualByComparingTo(new BigDecimal("0.0625"));
        assertThat(p.locals()).hasSize(1);
        assertThat(p.locals().getFirst().rate()).isEqualByComparingTo(new BigDecimal("0.02"));
    }
}
