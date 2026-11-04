package com.afrotransact.catalog.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.ProductTypeTraits;
import com.afrotransact.catalog.domain.VariationTheme;
import com.afrotransact.catalog.domain.sizing.ChartColumn;
import com.afrotransact.catalog.domain.sizing.ChartRow;
import com.afrotransact.catalog.domain.sizing.FitNote;
import com.afrotransact.catalog.domain.sizing.MeasurementUnit;
import com.afrotransact.catalog.domain.sizing.SizingFixtures;
import com.afrotransact.catalog.domain.sizing.SizingTrait;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** How sizing is stored in the jsonb columns, including rows written before it existed. */
class JsonCodecSizingTest {

    private final JsonCodec codec = new JsonCodec(JsonMapper.builder().build());

    @Test
    void sizingTraitsRoundTrip() {
        ProductTypeTraits traits = SizingFixtures.DRESS_CHART_REQUIRED.traits();

        ProductTypeTraits back = codec.traits(codec.write(codec.traitsDocument(traits)));

        assertThat(back).isEqualTo(traits);
        assertThat(back.sizing().sizeChartRequired()).isTrue();
        assertThat(back.sizing().suggestedMeasurements()).extracting(m -> m.key()).containsExactly("bust", "waist", "hip");
    }

    @Test
    void traitsStoredBeforeSizingHaveNone() {
        ProductTypeTraits legacy = codec.traits("{\"food\": true, \"perishable\": false, \"regulated\": false, "
                + "\"preorder\": true}");

        assertThat(legacy.food()).isTrue();
        assertThat(legacy.preorder()).isTrue();
        assertThat(legacy.sizing()).isNull();
        assertThat(codec.traits("{}")).isEqualTo(ProductTypeTraits.NONE);
    }

    @Test
    void themesStoredBeforeCustomValuesDoNotAllowThem() {
        List<VariationTheme> themes = codec.themes("[{\"name\": \"Size\", \"dimensions\": [{\"name\": \"Size\", "
                + "\"options\": [\"S\", \"M\"]}]}]");

        assertThat(themes.getFirst().dimension("Size").allowCustomValues()).isFalse();
        List<VariationTheme> custom = codec.themes(codec.write(SizingFixtures.DRESS.themes()));
        assertThat(custom.getFirst().dimension("Size").allowCustomValues()).isTrue();
    }

    @Test
    void theSizingTraitOfAMigratedTypeReads() {
        SizingTrait read = codec.traits("{\"food\": false, \"sizing\": {\"sizeChartRequired\": false, "
                + "\"sizeDimension\": \"Size\", \"suggestedMeasurements\": [{\"key\": \"bust\", \"label\": \"Bust\", "
                + "\"unit\": \"CM\", \"help\": \"Around the fullest part of the bust\"}]}}").sizing();

        assertThat(read.sizeChartRequired()).isFalse();
        assertThat(read.suggestedMeasurements().getFirst().unit()).isEqualTo(MeasurementUnit.CM);
    }

    @Test
    void listingDetailsKeepTheChartLinkAndOldListingsHaveNone() {
        UUID chart = UUID.randomUUID();
        ListingDetails linked = ListingDetails.DEFAULT.withSizing(chart, FitNote.RUNS_SMALL);

        ListingDetails back = codec.listing(codec.write(linked));

        assertThat(back.sizeChartId()).isEqualTo(chart);
        assertThat(back.fitNote()).isEqualTo(FitNote.RUNS_SMALL);
        ListingDetails old = codec.listing("{\"description\": \"Hand-ground\", \"condition\": \"NEW\", "
                + "\"keywords\": [], \"fulfilment\": \"SELLER\", \"handlingDays\": 2}");
        assertThat(old.sizeChartId()).isNull();
        assertThat(old.fitNote()).isNull();
        assertThat(old.description()).isEqualTo("Hand-ground");
    }

    @Test
    void chartColumnsAndRowsKeepTheirNumbers() {
        SizeChartRowsHolder holder = new SizeChartRowsHolder();
        List<ChartColumn> columns = codec.chartColumns(codec.write(holder.columns));
        List<ChartRow> rows = codec.chartRows(codec.write(holder.rows));

        assertThat(columns).isEqualTo(holder.columns);
        assertThat(rows.getFirst().size()).isEqualTo("UK 12");
        assertThat(rows.getFirst().values().get("bust").min()).isEqualByComparingTo("86.5");
        assertThat(rows.getFirst().values().get("bust").max()).isEqualByComparingTo("90");
        assertThat(rows.getFirst().values().get("waist").single()).isTrue();
    }

    private static final class SizeChartRowsHolder {
        private final List<ChartColumn> columns = SizingFixtures.columns(MeasurementUnit.CM, "Bust", "Waist");
        private final List<ChartRow> rows = List.of(new ChartRow("UK 12", Map.of(
                "bust", SizingFixtures.range("86.5", "90"), "waist", SizingFixtures.value("70"))));
    }
}
