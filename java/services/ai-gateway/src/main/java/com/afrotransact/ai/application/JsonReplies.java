package com.afrotransact.ai.application;

import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Extracts the JSON object or array a model was asked to return (tolerates prose and code fences around it). */
final class JsonReplies {

    private JsonReplies() {
    }

    static Optional<JsonNode> parse(JsonMapper json, String modelText, char open, char close) {
        if (modelText == null) {
            return Optional.empty();
        }
        int start = modelText.indexOf(open);
        int end = modelText.lastIndexOf(close);
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readTree(modelText.substring(start, end + 1)));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asString("").trim();
    }
}
