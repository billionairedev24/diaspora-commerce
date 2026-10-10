package com.afrotransact.content.adapter.out.schema;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.afrotransact.content.port.PayloadSchemaValidator;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JSON Schema 2020-12 validation (networknt, Jackson 3). Compiled schemas are cached by their text. */
@Component
class NetworkntPayloadSchemaValidator implements PayloadSchemaValidator {

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<String, Schema> compiled = new ConcurrentHashMap<>();
    private final JsonMapper mapper;

    NetworkntPayloadSchemaValidator(JsonMapper mapper) {
        this.mapper = mapper;
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
        Schema schema = compiled.computeIfAbsent(schemaJson, registry::getSchema);
        return schema.validate(node).stream().map(NetworkntPayloadSchemaValidator::describe).toList();
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
