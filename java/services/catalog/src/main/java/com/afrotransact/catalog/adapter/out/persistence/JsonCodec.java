package com.afrotransact.catalog.adapter.out.persistence;

import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.VariationTheme;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** JSON (jsonb) conversions for value objects stored as documents. */
@Component
class JsonCodec {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final TypeReference<List<ProductImage>> IMAGES = new TypeReference<>() { };
    private static final TypeReference<List<AttributeDefinition>> ATTRIBUTES = new TypeReference<>() { };
    private static final TypeReference<List<VariationTheme>> THEMES = new TypeReference<>() { };

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
}
