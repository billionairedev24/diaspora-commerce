package com.afrotransact.catalog.adapter.out.persistence;

import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductTypeTraits;
import com.afrotransact.catalog.domain.VariationTheme;
import com.afrotransact.catalog.domain.prefill.ProductData;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (jsonb) conversions for value objects stored as documents. */
@Component
class JsonCodec {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, Boolean>> FLAGS = new TypeReference<>() { };
    private static final TypeReference<List<ProductImage>> IMAGES = new TypeReference<>() { };
    private static final TypeReference<List<AttributeDefinition>> ATTRIBUTES = new TypeReference<>() { };
    private static final TypeReference<List<VariationTheme>> THEMES = new TypeReference<>() { };
    private static final TypeReference<List<ProductData>> PRODUCT_DATA = new TypeReference<>() { };

    private final JsonMapper mapper;

    JsonCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    Map<String, String> stringMap(String json) {
        return json == null ? Map.of() : mapper.readValue(json, STRING_MAP);
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

    /** Traits stored as {"food": true, ...}; absent keys are false. */
    ProductTypeTraits traits(String json) {
        if (json == null || json.isBlank()) {
            return ProductTypeTraits.NONE;
        }
        Map<String, Boolean> t = mapper.readValue(json, FLAGS);
        return new ProductTypeTraits(Boolean.TRUE.equals(t.get("food")), Boolean.TRUE.equals(t.get("perishable")),
                Boolean.TRUE.equals(t.get("regulated")), Boolean.TRUE.equals(t.get("preorder")));
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

    List<ProductData> productData(String json) {
        return json == null ? List.of() : mapper.readValue(json, PRODUCT_DATA);
    }
}
