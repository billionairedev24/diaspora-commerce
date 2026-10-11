package com.afrotransact.content.adapter.out.schema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.content.config.ContentProperties;
import com.afrotransact.content.port.PayloadSchemaValidator;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON Schema 2020-12 validation (networknt, Jackson 3). Compiled schemas are cached by their text.
 *
 * <p>Slot schemas require {@code https://} image URLs. The local profile serves media from MinIO over plain http
 * ({@code at.content.placements.local-image-origins}, e.g. {@code http://localhost:9000}): an {@code imageUrl} on one
 * of those origins is validated as if it were https. The stored payload is never rewritten.
 */
@Component
class NetworkntPayloadSchemaValidator implements PayloadSchemaValidator {

    static final String IMAGE_FIELD = "imageUrl";
    private static final String STAND_IN_ORIGIN = "https://local-media.invalid";

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<String, Schema> compiled = new ConcurrentHashMap<>();
    private final JsonMapper mapper;
    private final List<String> localImageOrigins;

    NetworkntPayloadSchemaValidator(JsonMapper mapper, ContentProperties properties) {
        this.mapper = mapper;
        this.localImageOrigins = properties.placements().localImageOrigins().stream()
                .map(o -> o.endsWith("/") ? o.substring(0, o.length() - 1) : o)
                .filter(o -> !o.isBlank())
                .toList();
    }

    @Override
    public List<String> validate(String schemaJson, String documentJson) {
        JsonNode node;
        try {
            node = mapper.readTree(documentJson);
        } catch (JacksonException ex) {
            return List.of("$: is not valid JSON");
        }
        if (node == null || !node.isObject()) {
            return List.of("$: must be a JSON object");
        }
        if (!localImageOrigins.isEmpty()) {
            node = node.deepCopy();
            acceptLocalImages(node);
        }
        Schema schema = compiled.computeIfAbsent(schemaJson, registry::getSchema);
        return schema.validate(node).stream().map(NetworkntPayloadSchemaValidator::describe).toList();
    }

    private void acceptLocalImages(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (String name : List.copyOf(object.propertyNames())) {
                JsonNode child = object.get(name);
                if (IMAGE_FIELD.equals(name) && child != null && child.isString()) {
                    String standIn = standIn(child.asString());
                    if (standIn != null) {
                        object.put(name, standIn);
                    }
                } else {
                    acceptLocalImages(child);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (JsonNode child : array) {
                acceptLocalImages(child);
            }
        }
    }

    private String standIn(String url) {
        for (String origin : localImageOrigins) {
            if (url.startsWith(origin + "/")) {
                return STAND_IN_ORIGIN + url.substring(origin.length());
            }
        }
        return null;
    }

    private static String describe(Error e) {
        String location = String.valueOf(e.getInstanceLocation());
        String message = e.getMessage();
        if (message.startsWith(location + ": ")) {
            message = message.substring(location.length() + 2);
        }
        return location + ": " + message;
    }
}
