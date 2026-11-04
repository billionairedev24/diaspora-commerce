package com.afrotransact.catalog.domain.sizing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.ProductTypeTraits;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.VariationDimension;
import com.afrotransact.catalog.domain.VariationTheme;

/** Sized product types and size charts shaped like the seeded fashion types, for tests. */
public final class SizingFixtures {

    public static final String OWNER = "org_adire";

    /** Women's dresses: sized, chart optional; sizes and colours are suggestions. */
    public static final ProductType DRESS = dress(false);
    /** Women's dresses where the category requires a chart. */
    public static final ProductType DRESS_CHART_REQUIRED = dress(true);

    /** Shoes: a size-only theme, foot length in centimetres. */
    public static final ProductType SHOES = new ProductType("shoes", "Shoes", List.of(
            new AttributeDefinition("brand", "Brand", List.of(), true, false)),
            List.of(new VariationTheme("Size", List.of(new VariationDimension("Size",
                    List.of("EU 36", "EU 37", "EU 38", "EU 39", "EU 40"), true)))),
            IdentifierRule.GTIN, new ProductTypeTraits(false, false, false, false, new SizingTrait(true, "Size",
                    List.of(new SuggestedMeasurement("foot_length", "Foot length", MeasurementUnit.CM,
                            "Heel to longest toe")))));

    /** A type that is not sized: Size is a fixed list. */
    public static final ProductType SPICES = new ProductType("grocery", "Packaged food", List.of(
            new AttributeDefinition("brand", "Brand", List.of(), true, false)),
            List.of(new VariationTheme("Size", List.of(new VariationDimension("Size", List.of("8 oz", "1 lb"))))),
            IdentifierRule.GTIN);

    private SizingFixtures() {
    }

    private static ProductType dress(boolean required) {
        return new ProductType("dress", "Women's dresses", List.of(
                new AttributeDefinition("brand", "Brand", List.of(), true, false)),
                List.of(new VariationTheme("Size × Color", List.of(
                                new VariationDimension("Size", List.of("XS", "S", "M", "L", "XL"), true),
                                new VariationDimension("Color", List.of("Black", "Gold", "Indigo"), true))),
                        new VariationTheme("Size", List.of(new VariationDimension("Size",
                                List.of("XS", "S", "M", "L", "XL"), true)))),
                IdentifierRule.GTIN, new ProductTypeTraits(false, false, false, false, new SizingTrait(required,
                        "Size", List.of(
                                new SuggestedMeasurement("bust", "Bust", MeasurementUnit.CM, "Fullest part of the bust"),
                                new SuggestedMeasurement("waist", "Waist", MeasurementUnit.CM, null),
                                new SuggestedMeasurement("hip", "Hip", MeasurementUnit.CM, null)))));
    }

    public static MeasurementRange value(String v) {
        return new MeasurementRange(new BigDecimal(v), null);
    }

    public static MeasurementRange range(String min, String max) {
        return new MeasurementRange(new BigDecimal(min), new BigDecimal(max));
    }

    /** A row with the same range in every column key. */
    public static ChartRow row(String size, String min, String max, String... keys) {
        Map<String, MeasurementRange> values = new LinkedHashMap<>();
        for (String key : keys) {
            values.put(key, range(min, max));
        }
        return new ChartRow(size, values);
    }

    public static List<ChartColumn> columns(MeasurementUnit unit, String... labels) {
        return Arrays.stream(labels).map(l -> new ChartColumn(SizeChartRules.keyFrom(l), l, unit)).toList();
    }

    /** A chart of S, M and L with bust, waist and hip. */
    public static SizeChart dressChart(String owner, String name) {
        return chart(owner, name, row("S", "84", "88", "bust", "waist", "hip"),
                row("M", "89", "93", "bust", "waist", "hip"), row("L", "94", "98", "bust", "waist", "hip"));
    }

    public static SizeChart chart(String owner, String name, ChartRow... rows) {
        Instant now = Instant.parse("2026-11-01T10:00:00Z");
        return new SizeChart(UUID.randomUUID(), owner, name, MeasurementUnit.CM, "dress", null,
                columns(MeasurementUnit.CM, "Bust", "Waist", "Hip"), List.of(rows), FitNote.TRUE_TO_SIZE, null, null,
                now, now, 0);
    }

    public static VariantDraft variant(String sku, String size) {
        return new VariantDraft(Map.of("Size", size), sku, "", true, 4900, 5);
    }
}
