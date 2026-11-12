package com.afrotransact.offers.testsupport;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/**
 * Reads contracts/openapi/&lt;service&gt;.yaml (the contract is written first, the code follows it) so a test can say: every
 * field a response carries is in the contract, every field the contract requires is carried, every field of a request body
 * the contract describes is accepted, and every route a controller serves is in the contract.
 */
public final class OpenApiContract {

    private final Map<String, Object> document;

    private OpenApiContract(Map<String, Object> document) {
        this.document = document;
    }

    /** The contract of a service; tests run in the service module, so the repository root is three levels up. */
    @SuppressWarnings("unchecked")
    public static OpenApiContract of(String service) {
        Path file = Path.of("..", "..", "..", "contracts", "openapi", service + ".yaml");
        try (Reader reader = Files.newBufferedReader(file)) {
            return new OpenApiContract((Map<String, Object>) new Yaml().load(reader));
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + file.toAbsolutePath(), ex);
        }
    }

    /** The property names of a component schema, following {@code $ref}s of properties' parents ({@code allOf}). */
    public Set<String> properties(String schema) {
        return new TreeSet<>(schema(schema).keySet());
    }

    /** The properties the contract requires. */
    @SuppressWarnings("unchecked")
    public Set<String> required(String schemaName) {
        Object req = component(schemaName).get("required");
        return req instanceof List<?> list ? new TreeSet<>((List<String>) list) : Set.of();
    }

    /** "GET /v1/catalog/settings"-style keys of every operation in the contract. */
    @SuppressWarnings("unchecked")
    public Set<String> routes() {
        Set<String> out = new LinkedHashSet<>();
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        paths.forEach((path, item) -> ((Map<String, Object>) item).keySet().stream()
                .filter(m -> Set.of("get", "post", "put", "patch", "delete").contains(m))
                .forEach(m -> out.add(m.toUpperCase(Locale.ROOT) + " " + path)));
        return out;
    }

    /** The enum values of a component schema. */
    @SuppressWarnings("unchecked")
    public Set<String> enumValues(String schemaName) {
        Object values = component(schemaName).get("enum");
        return values instanceof List<?> list ? new TreeSet<>((List<String>) list) : Set.of();
    }

    /** Names of the properties of {@code json} (an object). */
    public static Set<String> keys(JsonNode json) {
        Set<String> out = new TreeSet<>();
        json.propertyNames().forEach(out::add);
        return out;
    }

    /** "METHOD path" of every mapping of the controller classes, for comparing with {@link #routes()}. */
    public static Set<String> routesOf(Class<?>... controllers) {
        Set<String> out = new TreeSet<>();
        for (Class<?> c : controllers) {
            String base = c.isAnnotationPresent(RequestMapping.class) ? first(c.getAnnotation(RequestMapping.class).value())
                    : "";
            for (Method m : c.getDeclaredMethods()) {
                add(out, base, "GET", m.getAnnotation(GetMapping.class) == null ? null : first(m.getAnnotation(GetMapping.class).value()));
                add(out, base, "POST", m.getAnnotation(PostMapping.class) == null ? null : first(m.getAnnotation(PostMapping.class).value()));
                add(out, base, "PUT", m.getAnnotation(PutMapping.class) == null ? null : first(m.getAnnotation(PutMapping.class).value()));
                add(out, base, "PATCH", m.getAnnotation(PatchMapping.class) == null ? null : first(m.getAnnotation(PatchMapping.class).value()));
                add(out, base, "DELETE", m.getAnnotation(DeleteMapping.class) == null ? null : first(m.getAnnotation(DeleteMapping.class).value()));
            }
        }
        return out;
    }

    private static void add(Set<String> out, String base, String method, String path) {
        if (path != null) {
            out.add(method + " " + base + path);
        }
    }

    private static String first(String[] values) {
        return values.length == 0 ? "" : values[0];
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schema(String name) {
        Map<String, Object> schema = component(name);
        Object props = schema.get("properties");
        return props instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> component(String name) {
        Map<String, Object> components = (Map<String, Object>) document.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> schema = (Map<String, Object>) schemas.get(name);
        if (schema == null) {
            throw new IllegalArgumentException("The contract has no schema " + name);
        }
        return schema;
    }

    /** Convenience for tests that build a list of names. */
    public static List<String> sorted(Set<String> names) {
        return new ArrayList<>(new TreeSet<>(names));
    }
}
