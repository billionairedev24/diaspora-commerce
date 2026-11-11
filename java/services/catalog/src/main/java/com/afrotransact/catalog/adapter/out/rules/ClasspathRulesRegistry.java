package com.afrotransact.catalog.adapter.out.rules;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.domain.rules.CategoryRules;
import com.afrotransact.catalog.domain.rules.FieldRule;
import com.afrotransact.catalog.domain.rules.FieldStatus;
import com.afrotransact.catalog.domain.rules.RuleBook;
import com.afrotransact.catalog.domain.rules.RuleField;
import com.afrotransact.catalog.port.RulesRegistry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads {@code listing-rules/rules.json} (the build copy of {@code tools/listing-rules/rules.json}, the only source of
 * the rules). Reading is strict: an unknown field id, kind or status, or a kind without a status for a field, stops the
 * service at start instead of letting a listing slip through.
 */
@Component
public class ClasspathRulesRegistry implements RulesRegistry {

    /** Where the build puts the rules. */
    public static final String RESOURCE = "listing-rules/rules.json";

    private final RuleBook book;

    public ClasspathRulesRegistry() {
        this(RESOURCE);
    }

    public ClasspathRulesRegistry(String resource) {
        try (InputStream in = ClasspathRulesRegistry.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Listing rules not found on the classpath: " + resource);
            }
            this.book = parse(JsonMapper.builder().build().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public RuleBook book() {
        return book;
    }

    /** Builds the rule book from rules.json content (public for the drift and coverage tests). */
    public static RuleBook parse(JsonNode root) {
        Map<String, String> kinds = new LinkedHashMap<>();
        root.get("kinds").forEach(k -> kinds.put(k.get("code").asString(), k.get("name").asString()));
        List<JsonNode> fieldNodes = new ArrayList<>();
        root.get("fields").forEach(fieldNodes::add);
        for (JsonNode f : fieldNodes) {
            String id = f.get("id").asString();
            RuleField.ofId(id).orElseThrow(() -> new IllegalStateException("Unknown listing field id: " + id));
        }
        if (fieldNodes.size() != RuleField.values().length) {
            throw new IllegalStateException("rules.json has " + fieldNodes.size() + " fields, expected "
                    + RuleField.values().length);
        }
        Map<String, CategoryRules> categories = new LinkedHashMap<>();
        for (JsonNode c : root.get("categories")) {
            String code = c.get("code").asString();
            String kind = c.get("kind").asString();
            if (!kinds.containsKey(kind)) {
                throw new IllegalStateException("Category " + code + " has unknown kind " + kind);
            }
            Map<String, FieldStatus> overrides = new LinkedHashMap<>();
            if (c.has("overrides")) {
                c.get("overrides").properties().forEach(e -> overrides.put(e.getKey(), FieldStatus.ofCode(e.getValue().asString())));
            }
            List<FieldRule> rules = new ArrayList<>();
            for (JsonNode f : fieldNodes) {
                RuleField field = RuleField.ofId(f.get("id").asString()).orElseThrow();
                JsonNode cell = f.get("byKind").get(kind);
                if (cell == null) {
                    throw new IllegalStateException("Field " + field.id() + " has no status for kind " + kind);
                }
                FieldStatus status = overrides.getOrDefault(field.id(), FieldStatus.ofCode(cell.asString()));
                rules.add(new FieldRule(field, f.get("group").asString(), f.get("name").asString(), status,
                        f.has("note") ? f.get("note").asString() : ""));
            }
            categories.put(code, new CategoryRules(code, c.get("name").asString(), c.get("department").asString(), kind,
                    kinds.get(kind), c.get("approval").asString(), c.has("note") ? c.get("note").asString() : "",
                    c.has("provisional") && c.get("provisional").asBoolean(), rules));
        }
        return new RuleBook(kinds, categories);
    }
}
