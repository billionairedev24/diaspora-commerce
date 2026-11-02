package com.afrotransact.catalog.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.ChartRequest;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.ChartResponse;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.ColumnDto;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.PublicChartResponse;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.RowRequest;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.SuggestionsResponse;
import com.afrotransact.catalog.adapter.in.web.SizeChartDtos.ValueDto;
import com.afrotransact.catalog.application.SizeChartService.ChartView;
import com.afrotransact.catalog.application.SizeChartService.Suggestions;
import com.afrotransact.catalog.domain.sizing.ChartContent;
import com.afrotransact.catalog.domain.sizing.FitNote;
import com.afrotransact.catalog.domain.sizing.MeasurementUnit;
import com.afrotransact.catalog.domain.sizing.SizeChart;
import com.afrotransact.catalog.domain.sizing.SizingFixtures;
import org.junit.jupiter.api.Test;

/** The mapping between the size chart API shapes and the domain. */
class SizeChartDtosTest {

    @Test
    void aRequestBecomesChartContentAndDropsAbsentValues() {
        Map<String, ValueDto> values = new LinkedHashMap<>();
        values.put("bust", new ValueDto(new BigDecimal("86"), new BigDecimal("90")));
        values.put("waist", new ValueDto(new BigDecimal("70"), null));
        values.put("hip", null);
        ChartRequest req = new ChartRequest("Adire dresses", MeasurementUnit.CM, "dress", null,
                List.of(new ColumnDto(null, "Bust", null), new ColumnDto("waist", "Waist", MeasurementUnit.CM)),
                List.of(new RowRequest("UK 12", values)), FitNote.RUNS_LARGE, "Measure over light clothes", 3L);

        ChartContent content = req.toContent();

        assertThat(content.columns()).extracting(c -> c.key(), c -> c.label()).containsExactly(
                tuple("", "Bust"), tuple("waist", "Waist"));
        assertThat(content.rows().getFirst().values()).containsOnlyKeys("bust", "waist");
        assertThat(content.rows().getFirst().values().get("waist").single()).isTrue();
        assertThat(content.fitNote()).isEqualTo(FitNote.RUNS_LARGE);
    }

    @Test
    void aResponseShowsTheOtherUnitByConversion() {
        SizeChart chart = SizingFixtures.dressChart("org_adire", "Adire dresses");

        ChartResponse res = ChartResponse.of(new ChartView(chart, 4));

        assertThat(res.unitSystem()).isEqualTo(MeasurementUnit.CM);
        assertThat(res.alternateUnit()).isEqualTo(MeasurementUnit.IN);
        assertThat(res.productCount()).isEqualTo(4);
        assertThat(res.retired()).isFalse();
        var row = res.rows().getFirst();
        assertThat(row.values().get("bust").min()).isEqualByComparingTo("84");
        assertThat(row.alternateValues().get("bust").min()).isEqualByComparingTo("33.1");
        assertThat(row.alternateValues().get("bust").max()).isEqualByComparingTo("34.6");
    }

    @Test
    void thePublicChartCarriesTheProductsFitNoteElseTheCharts() {
        SizeChart chart = SizingFixtures.dressChart("org_adire", "Adire dresses");

        assertThat(PublicChartResponse.of(chart, FitNote.RUNS_SMALL).fitNote()).isEqualTo(FitNote.RUNS_SMALL);
        assertThat(PublicChartResponse.of(chart, null).fitNote()).isEqualTo(FitNote.TRUE_TO_SIZE);
        assertThat(PublicChartResponse.of(chart, null).columns()).extracting(ColumnDto::label)
                .containsExactly("Bust", "Waist", "Hip");
    }

    @Test
    void suggestionsListTheSizesAndMeasurementsOfASizedType() {
        SuggestionsResponse res = SuggestionsResponse.of(
                new Suggestions("dress", "Women's dresses", true, true, "Size", List.of("XS", "S"), true,
                        MeasurementUnit.CM, SizingFixtures.DRESS.sizing().orElseThrow().suggestedMeasurements()));

        assertThat(res.sized()).isTrue();
        assertThat(res.sizes()).containsExactly("XS", "S");
        assertThat(res.measurements()).extracting(m -> m.key()).containsExactly("bust", "waist", "hip");
        assertThat(res.measurements().getFirst().help()).isEqualTo("Fullest part of the bust");
    }
}
