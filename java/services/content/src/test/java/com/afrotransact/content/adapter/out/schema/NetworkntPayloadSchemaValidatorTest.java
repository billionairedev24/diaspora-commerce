package com.afrotransact.content.adapter.out.schema;

import java.util.List;

import com.afrotransact.content.config.ContentProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class NetworkntPayloadSchemaValidatorTest {

    private static final String SCHEMA = """
            {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object",
             "additionalProperties": false, "required": ["title"],
             "properties": {"title": {"type": "string"},
               "imageUrl": {"type": ["string", "null"], "maxLength": 500, "pattern": "^https://"},
               "cells": {"type": "array", "items": {"type": "object", "properties": {
                 "imageUrl": {"type": ["string", "null"], "pattern": "^https://"}}}}}}
            """;

    private static NetworkntPayloadSchemaValidator validator(List<String> localOrigins) {
        ContentProperties props = new ContentProperties(null, null, null,
                new ContentProperties.Placements(null, null, null, null, localOrigins), null, null, null, null);
        return new NetworkntPayloadSchemaValidator(JsonMapper.builder().build(), props);
    }

    @Test
    void imagesMustBeHttpsByDefault() {
        List<String> problems = validator(List.of()).validate(SCHEMA,
                "{\"title\": \"Kente\", \"imageUrl\": \"http://localhost:9000/media/storefront/a.jpg\"}");

        assertThat(problems).singleElement().asString().startsWith("/imageUrl");
    }

    @Test
    void localMediaOriginsAreAcceptedAsHttpsIncludingNestedCells() {
        NetworkntPayloadSchemaValidator v = validator(List.of("http://localhost:9000/"));

        assertThat(v.validate(SCHEMA, "{\"title\": \"Kente\", \"imageUrl\": \"http://localhost:9000/media/a.jpg\","
                + " \"cells\": [{\"imageUrl\": \"http://localhost:9000/media/b.jpg\"}, {\"imageUrl\": null}]}"))
                .isEmpty();
        assertThat(v.validate(SCHEMA, "{\"title\": \"Kente\", \"imageUrl\": \"http://evil.example/a.jpg\"}"))
                .singleElement().asString().startsWith("/imageUrl");
        assertThat(v.validate(SCHEMA, "{\"title\": \"Kente\", \"imageUrl\": \"http://localhost:90001/a.jpg\"}"))
                .singleElement().asString().startsWith("/imageUrl");
    }

    @Test
    void onlyImageFieldsAreRelaxed() {
        String schema = "{\"type\": \"object\", \"properties\": {\"href\": {\"type\": \"string\", \"pattern\": \"^https://\"}}}";

        assertThat(validator(List.of("http://localhost:9000")).validate(schema,
                "{\"href\": \"http://localhost:9000/x\"}")).isNotEmpty();
    }
}
