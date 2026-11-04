package com.afrotransact.catalog.domain.sizing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import org.junit.jupiter.api.Test;

/** What a size chart must satisfy, with the field names the chart editor shows messages on. */
class SizeChartRulesTest {

    private static ChartContent chart(List<ChartColumn> columns, ChartRow... rows) {
        return new ChartContent("Adire dresses", MeasurementUnit.CM, "dress", null, columns, List.of(rows),
                FitNote.TRUE_TO_SIZE, "Measure over light clothing.");
    }

    private static final List<ChartColumn> BUST_WAIST = SizingFixtures.columns(MeasurementUnit.CM, "Bust", "Waist");

    private static Map<String, String> errors(ChartContent content) {
        return SizeChartRules.check(content).errors();
    }

    @Test
    void aCompleteChartIsKeptWithKeysAndRoundedNumbers() {
        ChartRow m = new ChartRow(" M ", Map.of("bust", SizingFixtures.value("88.504"), "waist", SizingFixtures.range("70", "74.0")));
        ChartContent ok = SizeChartRules.validate(chart(List.of(new ChartColumn("", "  Bust ", null),
                new ChartColumn("waist", "Waist", MeasurementUnit.CM)), m));

        assertThat(ok.columns()).extracting(ChartColumn::key, ChartColumn::label)
                .containsExactly(tuple("bust", "Bust"),
                        tuple("waist", "Waist"));
        assertThat(ok.rows().getFirst().size()).isEqualTo("M");
        assertThat(ok.rows().getFirst().values().get("bust").min()).isEqualByComparingTo("88.5");
        assertThat(ok.rows().getFirst().values().get("waist").max()).isEqualTo(new BigDecimal("74"));
    }

    @Test
    void aNameIsRequired() {
        assertThat(errors(new ChartContent(" ", MeasurementUnit.CM, null, null, BUST_WAIST,
                List.of(SizingFixtures.row("M", "88", "90", "bust", "waist")), null, null))).containsKey("name");
    }

    @Test
    void theUnitIsRequired() {
        assertThat(errors(new ChartContent("Chart", null, null, null, BUST_WAIST,
                List.of(SizingFixtures.row("M", "88", "90", "bust", "waist")), null, null))).containsKey("unitSystem");
    }

    @Test
    void sizesMustDifferIgnoringCaseAndSpacing() {
        Map<String, String> errors = errors(chart(BUST_WAIST, SizingFixtures.row("XL", "100", "104", "bust", "waist"),
                SizingFixtures.row(" xl ", "105", "109", "bust", "waist")));

        assertThat(errors).containsKey("rows[1].size");
        assertThat(errors.get("rows[1].size")).contains("Duplicate size xl");
    }

    @Test
    void customSizeLabelsAreAccepted() {
        ChartContent ok = SizeChartRules.validate(chart(BUST_WAIST, SizingFixtures.row("UK 12", "88", "90", "bust", "waist"),
                SizingFixtures.row("Free size", "90", "110", "bust", "waist"), SizingFixtures.row("Age 6-7", "50", "54", "bust", "waist")));

        assertThat(ok.rows()).extracting(ChartRow::size).containsExactly("UK 12", "Free size", "Age 6-7");
    }

    @Test
    void aSizeLabelWithOddCharactersOrTooLongIsRefused() {
        assertThat(errors(chart(BUST_WAIST, SizingFixtures.row("<b>M</b>", "88", "90", "bust", "waist")))).containsKey("rows[0].size");
        assertThat(errors(chart(BUST_WAIST, SizingFixtures.row("M".repeat(31), "88", "90", "bust", "waist"))))
                .containsKey("rows[0].size");
    }

    @Test
    void everyColumnNeedsAValueOnEveryRow() {
        Map<String, String> errors = errors(chart(BUST_WAIST, new ChartRow("M", Map.of("bust", SizingFixtures.value("88")))));

        assertThat(errors).containsEntry("rows[0].values.waist", "Enter the waist of size M");
    }

    @Test
    void theSmallestValueCannotBeAboveTheLargest() {
        Map<String, String> errors = errors(chart(BUST_WAIST, new ChartRow("M",
                Map.of("bust", SizingFixtures.range("92", "88"), "waist", SizingFixtures.value("70")))));

        assertThat(errors.get("rows[0].values.bust")).contains("smallest value (92) is above the largest (88)");
    }

    @Test
    void measurementsMustBePositiveAndBounded() {
        Map<String, String> errors = errors(chart(BUST_WAIST, new ChartRow("M",
                Map.of("bust", SizingFixtures.value("0"), "waist", SizingFixtures.value("1001")))));

        assertThat(errors).containsKeys("rows[0].values.bust", "rows[0].values.waist");
    }

    @Test
    void aValueForAColumnTheChartDoesNotHaveIsRefused() {
        Map<String, String> errors = errors(chart(BUST_WAIST, new ChartRow("M",
                Map.of("bust", SizingFixtures.value("88"), "waist", SizingFixtures.value("70"), "shoulder", SizingFixtures.value("40")))));

        assertThat(errors).containsEntry("rows[0].values.shoulder", "Not a measurement of this chart");
    }

    @Test
    void columnsNeedDifferentLabelsAndKeysAndTheChartsUnit() {
        List<ChartColumn> cols = new ArrayList<>();
        cols.add(new ChartColumn("bust", "Bust", MeasurementUnit.CM));
        cols.add(new ChartColumn("bust", "Bust", MeasurementUnit.IN));
        Map<String, String> errors = errors(chart(cols, SizingFixtures.row("M", "88", "90", "bust")));

        assertThat(errors).containsKeys("columns[1].label", "columns[1].key", "columns[1].unit");
    }

    @Test
    void aChartNeedsColumnsAndRowsWithinTheLimits() {
        assertThat(errors(chart(List.of(), SizingFixtures.row("M", "88", "90", "bust")))).containsKey("columns");
        assertThat(errors(chart(BUST_WAIST))).containsKey("rows");
        List<ChartRow> many = new ArrayList<>();
        for (int i = 0; i <= SizeChartRules.MAX_ROWS; i++) {
            many.add(SizingFixtures.row("S" + i, "88", "90", "bust", "waist"));
        }
        assertThat(errors(new ChartContent("Chart", MeasurementUnit.CM, null, null, BUST_WAIST, many, null, null)))
                .containsKey("rows");
    }

    @Test
    void theHowToMeasureNoteHasALimit() {
        ChartContent c = new ChartContent("Chart", MeasurementUnit.CM, null, null, BUST_WAIST,
                List.of(SizingFixtures.row("M", "88", "90", "bust", "waist")), null, "x".repeat(1001));

        assertThat(errors(c)).containsKey("howToMeasure");
    }

    @Test
    void validateThrowsWithEveryProblem() {
        assertThatThrownBy(() -> SizeChartRules.validate(new ChartContent("", null, null, null, List.of(), List.of(),
                null, null))).isInstanceOf(CatalogRuleViolation.class)
                .satisfies(ex -> assertThat(((CatalogRuleViolation) ex).fields())
                        .contains("name", "unitSystem", "columns", "rows"));
    }

    @Test
    void columnKeysComeFromLabels() {
        assertThat(SizeChartRules.keyFrom("Foot length")).isEqualTo("foot_length");
        assertThat(SizeChartRules.keyFrom("Hip (cm)")).isEqualTo("hip_cm");
        assertThat(SizeChartRules.keyFrom("2nd shoulder")).isEqualTo("m_2nd_shoulder");
        assertThat(SizeChartRules.keyFrom("!!!")).isEmpty();
    }

    @Test
    void contentsAreComparedByValueNotByScale() {
        ChartContent a = chart(BUST_WAIST, new ChartRow("M", Map.of("bust", SizingFixtures.value("88"), "waist", SizingFixtures.value("70.0"))));
        ChartContent b = chart(BUST_WAIST, new ChartRow("M", Map.of("bust", SizingFixtures.value("88.00"), "waist", SizingFixtures.value("70"))));
        ChartContent c = chart(BUST_WAIST, new ChartRow("M", Map.of("bust", SizingFixtures.value("88"), "waist", SizingFixtures.value("71"))));

        assertThat(SizeChartRules.same(a, b)).isTrue();
        assertThat(SizeChartRules.same(a, c)).isFalse();
    }

    @Test
    void unitsConvertBothWaysWithOneDecimal() {
        assertThat(MeasurementUnit.CM.convert(new BigDecimal("88"), MeasurementUnit.IN)).isEqualByComparingTo("34.6");
        assertThat(MeasurementUnit.IN.convert(new BigDecimal("34"), MeasurementUnit.CM)).isEqualByComparingTo("86.4");
        assertThat(MeasurementUnit.CM.other()).isEqualTo(MeasurementUnit.IN);
    }
}
