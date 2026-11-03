package com.afrotransact.catalog.domain.sizing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.sizing.SizeChartPolicy.SizedListing;
import org.junit.jupiter.api.Test;

/** The size rules of a listing: a chart where the category requires one, the seller's own, a row for every size sold. */
class DefaultSizeChartPolicyTest {

    private final SizeChartPolicy policy = new DefaultSizeChartPolicy();

    private Map<String, String> problems(ProductType type, SizeChart chart, UUID chartId, UUID linked,
                                         VariantDraft... variants) {
        return policy.problems(new SizedListing(type, SizingFixtures.OWNER, "Size", List.of(variants), chartId, chart, linked));
    }

    @Test
    void aRequiredChartMustBeLinked() {
        Map<String, String> errors = problems(SizingFixtures.DRESS_CHART_REQUIRED, null, null, null, SizingFixtures.variant("A-M", "M"));

        assertThat(errors).containsOnlyKeys("sizeChartId");
        assertThat(errors.get("sizeChartId")).contains("Add a size chart").contains("Women's dresses");
    }

    @Test
    void anOptionalChartMayBeLeftOut() {
        assertThat(problems(SizingFixtures.DRESS, null, null, null, SizingFixtures.variant("A-M", "M"))).isEmpty();
    }

    @Test
    void everySizeTheVariantsSellNeedsARowInTheChart() {
        SizeChart chart = SizingFixtures.dressChart(SizingFixtures.OWNER, "Adire dresses");

        Map<String, String> errors = problems(SizingFixtures.DRESS_CHART_REQUIRED, chart, chart.id(), null, SizingFixtures.variant("A-S", "S"),
                SizingFixtures.variant("A-XL", "XL"), SizingFixtures.variant("A-FREE", "Free size"));

        assertThat(errors).containsOnlyKeys("variants[1].options.Size", "variants[2].options.Size");
        assertThat(errors.get("variants[1].options.Size")).contains("Size \"XL\" has no row in the size chart")
                .contains("Adire dresses");
    }

    @Test
    void sizesMatchTheChartIgnoringCaseAndSpacing() {
        SizeChart chart = SizingFixtures.dressChart(SizingFixtures.OWNER, "Adire dresses");

        assertThat(problems(SizingFixtures.DRESS, chart, chart.id(), null, SizingFixtures.variant("A-S", " s "), SizingFixtures.variant("A-M", "m"))).isEmpty();
    }

    @Test
    void customSizesAreFineWhenTheChartHasThem() {
        SizeChart chart = SizingFixtures.chart(SizingFixtures.OWNER, "UK sizes",
                SizingFixtures.row("UK 12", "88", "90", "bust", "waist", "hip"),
                SizingFixtures.row("Free size", "90", "110", "bust", "waist", "hip"));

        assertThat(problems(SizingFixtures.DRESS, chart, chart.id(), null, SizingFixtures.variant("A-12", "UK 12"), SizingFixtures.variant("A-F", "Free size")))
                .isEmpty();
    }

    @Test
    void theRowRuleAppliesWhenAnOptionalChartIsGiven() {
        SizeChart chart = SizingFixtures.dressChart(SizingFixtures.OWNER, "Adire dresses");

        assertThat(problems(SizingFixtures.DRESS, chart, chart.id(), null, SizingFixtures.variant("A-XS", "XS")))
                .containsOnlyKeys("variants[0].options.Size");
    }

    @Test
    void aChartOfAnotherSellerCanNeverBeLinked() {
        SizeChart foreign = SizingFixtures.dressChart("org_other", "Their dresses");

        Map<String, String> errors = problems(SizingFixtures.DRESS, foreign, foreign.id(), null, SizingFixtures.variant("A-S", "S"));

        assertThat(errors).containsEntry("sizeChartId", "Size chart not found");
    }

    @Test
    void anUnknownChartIsNotFound() {
        assertThat(problems(SizingFixtures.DRESS, null, UUID.randomUUID(), null, SizingFixtures.variant("A-S", "S")))
                .containsEntry("sizeChartId", "Size chart not found");
    }

    @Test
    void aRetiredChartCannotBeLinkedNewlyButStaysOnAProductThatHasIt() {
        SizeChart retired = SizingFixtures.dressChart(SizingFixtures.OWNER, "Old dresses").retire(Instant.parse("2026-11-02T10:00:00Z"));

        assertThat(problems(SizingFixtures.DRESS, retired, retired.id(), null, SizingFixtures.variant("A-S", "S")).get("sizeChartId"))
                .contains("was retired");
        assertThat(problems(SizingFixtures.DRESS, retired, retired.id(), retired.id(), SizingFixtures.variant("A-S", "S"))).isEmpty();
    }

    @Test
    void aTypeWithoutSizingTakesNoChart() {
        SizeChart chart = SizingFixtures.dressChart(SizingFixtures.OWNER, "Adire dresses");

        assertThat(problems(SizingFixtures.SPICES, chart, chart.id(), null, SizingFixtures.variant("S-1", "8 oz")).get("sizeChartId"))
                .contains("not used for Packaged food");
        assertThat(problems(SizingFixtures.SPICES, null, null, null, SizingFixtures.variant("S-1", "8 oz"))).isEmpty();
    }

    @Test
    void aLinkSurvivesTheTypeLosingItsSizing() {
        SizeChart chart = SizingFixtures.dressChart(SizingFixtures.OWNER, "Adire dresses");

        assertThat(problems(SizingFixtures.SPICES, chart, chart.id(), chart.id(), SizingFixtures.variant("S-1", "8 oz"))).isEmpty();
    }

    @Test
    void shoesWithOnlyASizeThemeAreCheckedToo() {
        SizeChart chart = SizingFixtures.chart(SizingFixtures.OWNER, "Shoe sizes",
                SizingFixtures.row("EU 38", "24", "24.5", "bust", "waist", "hip"));

        assertThat(problems(SizingFixtures.SHOES, chart, chart.id(), null, SizingFixtures.variant("S-38", "EU 38"),
                SizingFixtures.variant("S-41", "EU 41"))).containsOnlyKeys("variants[1].options.Size");
    }
}
