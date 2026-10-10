package com.afrotransact.content.port;

import java.util.List;

/** Validates JSON documents against JSON Schemas (placement payloads against slot schemas). */
public interface PayloadSchemaValidator {

    /** Human-readable problems ({@code $.title: must be at most 60 characters}); empty when valid. */
    List<String> validate(String schemaJson, String documentJson);
}
