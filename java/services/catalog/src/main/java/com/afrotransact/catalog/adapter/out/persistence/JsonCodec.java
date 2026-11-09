package com.afrotransact.catalog.adapter.out.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductTypeTraits;
import com.afrotransact.catalog.domain.VariationTheme;
import com.afrotransact.catalog.domain.sizing.ChartColumn;
import com.afrotransact.catalog.domain.tax.MissingTaxFact;
import com.afrotransact.catalog.domain.tax.TaxDecision;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.domain.tax.VariantTax;
import com.afrotransact.catalog.domain.sizing.ChartRow;
import com.afrotransact.catalog.domain.sizing.SizingTrait;
import com.afrotransact.catalog.domain.prefill.ProductData;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (jsonb) conversions for value objects stored as documents. */
@Component
class JsonCodec {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };
    private static final TypeReference<List<ProductImage>> IMAGES = new TypeReference<>() { };
    private static final TypeReference<List<AttributeDefinition>> ATTRIBUTES = new TypeReference<>() { };
    private static final TypeReference<List<VariationTheme>> THEMES = new TypeReference<>() { };
    private static final TypeReference<List<ChartColumn>> CHART_COLUMNS = new TypeReference<>() { };
    private static final TypeReference<List<ChartRow>> CHART_ROWS = new TypeReference<>() { };
    private static final TypeReference<List<ProductData>> PRODUCT_DATA = new TypeReference<>() { };
    private static final TypeReference<List<MissingTaxFact>> MISSING_FACTS = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final JsonMapper mapper;

    JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    /** A variant's tax state as stored in its columns. */
    VariantTax variantTax(String facts, String status, String missing, String decision) {
        TaxStatus parsed = TaxStatus.parse(status);
        return new VariantTax(objectMap(facts), parsed == null ? TaxStatus.MISSING_FACTS : parsed,
                missing == null || missing.isBlank() ? List.of() : mapper.readValue(missing, MISSING_FACTS),
                decision == null || decision.isBlank() ? null : mapper.readValue(decision, TaxDecision.class));
    }

    String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    Map<String, String> stringMap(String json) {
        return json == null ? Map.of() : mapper.readValue(json, STRING_MAP);
    }

    /** A JSON object with values of any type (audit details). */
    Map<String, Object> objectMap(String json) {
        return json == null || json.isBlank() ? Map.of() : mapper.readValue(json, OBJECT_MAP);
    }

    List<ProductImage> images(String json) {
        return json == null ? List.of() : mapper.readValue(json, IMAGES);
    }

    List<AttributeDefinition> attributes(String json) {
        return json == null ? List.of() : mapper.readValue(json, ATTRIBUTES);
    }

    List<VariationTheme> themes(String json) {
        return json == null ? List.of() : mapper.readValue(json, THEMES);
    }

    /** Traits stored as {"food": true, ..., "sizing": {...}}; absent flags are false and absent sizing is none. */
    ProductTypeTraits traits(String json) {
        if (json == null || json.isBlank()) {
            return ProductTypeTraits.NONE;
        }
        Map<String, Object> t = mapper.readValue(json, OBJECT_MAP);
        Object sizing = t.get("sizing");
        return new ProductTypeTraits(flag(t, "food"), flag(t, "perishable"), flag(t, "regulated"), flag(t, "preorder"),
                sizing == null ? null : mapper.convertValue(sizing, SizingTrait.class));
    }

    private static boolean flag(Map<String, Object> traits, String name) {
        return Boolean.TRUE.equals(traits.get(name));
    }

    /** The stored form of traits: the flags and, for a sized type, the sizing trait. */
    Map<String, Object> traitsDocument(ProductTypeTraits t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("food", t.food());
        out.put("perishable", t.perishable());
        out.put("regulated", t.regulated());
        out.put("preorder", t.preorder());
        if (t.sizing() != null) {
            out.put("sizing", t.sizing());
        }
        return out;
    }

    /** Listing details; products saved before S307 ({@code {}}) read as the defaults. */
    ListingDetails listing(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.strip())) {
            return ListingDetails.DEFAULT;
        }
        return mapper.readValue(json, ListingDetails.class);
    }

    <T> T read(String json, Class<T> type) {
        return mapper.readValue(json, type);
    }

    /** A JSON array of strings (review photo URLs). */
    List<String> strings(String json) {
        return json == null || json.isBlank() ? List.of() : mapper.readValue(json, STRINGS);
    }

    List<ChartColumn> chartColumns(String json) {
        return json == null ? List.of() : mapper.readValue(json, CHART_COLUMNS);
    }

    List<ChartRow> chartRows(String json) {
        return json == null ? List.of() : mapper.readValue(json, CHART_ROWS);
    }

    List<ProductData> productData(String json) {
        return json == null ? List.of() : mapper.readValue(json, PRODUCT_DATA);
    }
}
