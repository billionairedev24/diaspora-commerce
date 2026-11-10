package com.afrotransact.catalog.adapter.out.taxonomy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyDataset;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomySchemas;
import com.afrotransact.catalog.port.TaxonomySource;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the bundled dataset ({@code taxonomy/category_tax_map.json}, copied from tools/taxonomy at build time) and
 * schemas ({@code taxonomy/schemas.json}) from the classpath.
 */
@Component
public class ClasspathTaxonomySource implements TaxonomySource {

    private static final TypeReference<Map<String, Object>> DOCUMENT = new TypeReference<>() { };
    private static final String DATASET = "taxonomy/category_tax_map.json";
    private static final String SCHEMAS = "taxonomy/schemas.json";

    private final JsonMapper mapper;

    public ClasspathTaxonomySource() {
        this(JsonMapper.builder().build());
    }

    public ClasspathTaxonomySource(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public TaxonomyDataset bundled() {
        return parse(read(DATASET));
    }

    @Override
    public Map<String, Object> bundledDocument() {
        return read(DATASET);
    }

    @Override
    public TaxonomyDataset parse(Map<String, Object> document) {
        return TaxonomyDataset.of(document);
    }

    @Override
    public TaxonomySchemas schemas() {
        return new TaxonomySchemas(read(SCHEMAS));
    }

    private Map<String, Object> read(String resource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + resource);
            }
            return mapper.readValue(in, DOCUMENT);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
