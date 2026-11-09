package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.catalog.adapter.out.taxonomy.ClasspathTaxonomySource;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner.Plan;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner.PlannedNode;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * V20: loads the department > category > product type tree of docs/engineering/category-tax-design.md (13 departments, 73
 * categories, their product types and tax questions, plus the provisional "Fabrics &amp; textiles") for a new database.
 *
 * <p>It is the one-time replacement of the initial tree that V2 seeded (retired by V19), the owner's decision because
 * nothing was deployed: this migration writes rows staff would otherwise own, once, and only creates what is missing
 * (by code). Every later change goes through the staff console or the importer ({@code POST /v1/taxonomy/import}),
 * never through a migration. See docs/engineering/taxonomy.md.
 */
@Component
class CategoryTaxTreeMigration extends BaseJavaMigration {

    private static final String INSERT_TYPE = """
            insert into product_type (code, label, identifier_rule, attributes, themes, traits, version, updated_at)
            values (?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), 0, now())
            on conflict (code) do nothing
            """;
    private static final String INSERT_NODE = """
            insert into category (id, parent_id, code, name, level, product_type_code, path, position, active, version,
                                  updated_at, compliance, tax_profile_id, tax_status)
            values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, true, 0, now(), ?, ?, ?)
            on conflict (code) do nothing
            """;

    /** Registered as a Spring bean (Flyway runs it as V20): Java migration class names would break the naming rules. */
    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("20");
    }

    @Override
    public String getDescription() {
        return "load category tax tree";
    }

    @Override
    public void migrate(Context context) throws Exception {
        ClasspathTaxonomySource source = new ClasspathTaxonomySource();
        Plan plan = TaxonomyPlanner.plan(source.bundled(), source.schemas());
        JsonMapper json = JsonMapper.builder().build();
        Connection c = context.getConnection();
        try (PreparedStatement types = c.prepareStatement(INSERT_TYPE)) {
            for (ProductType t : plan.types()) {
                types.setString(1, t.code());
                types.setString(2, t.label());
                types.setString(3, t.identifierRule().name());
                types.setString(4, json.writeValueAsString(t.attributes()));
                types.setString(5, json.writeValueAsString(t.themes()));
                types.setString(6, json.writeValueAsString(traits(t)));
                types.addBatch();
            }
            types.executeBatch();
        }
        try (PreparedStatement nodes = c.prepareStatement(INSERT_NODE)) {
            for (CategoryLevel level : List.of(CategoryLevel.DEPARTMENT, CategoryLevel.CATEGORY, CategoryLevel.PRODUCT_TYPE)) {
                for (PlannedNode n : plan.nodes()) {
                    if (n.level() != level) {
                        continue;
                    }
                    nodes.setString(1, n.id().toString());
                    nodes.setString(2, n.parentId() == null ? null : n.parentId().toString());
                    nodes.setString(3, n.code());
                    nodes.setString(4, n.name());
                    nodes.setString(5, n.level().name());
                    nodes.setString(6, n.productTypeCode());
                    nodes.setString(7, n.path());
                    nodes.setInt(8, n.position());
                    nodes.setString(9, n.compliance().isEmpty() ? null : String.join(",", n.compliance()));
                    nodes.setString(10, n.taxProfileId());
                    nodes.setString(11, n.taxStatus());
                    nodes.addBatch();
                }
                nodes.executeBatch();
            }
        }
        try (Statement st = c.createStatement()) {
            // Starter brands keep where they apply (V19 recorded the scopes of the retired nodes).
            st.execute("""
                    insert into brand_scope (brand_id, node_id)
                    select distinct l.brand_id, c.id from legacy_brand_scope l join category c on c.code = l.target_code
                    on conflict do nothing
                    """);
            st.execute("drop table legacy_brand_scope");
        }
    }

    private static Map<String, Object> traits(ProductType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("food", t.traits().food());
        m.put("perishable", t.traits().perishable());
        m.put("regulated", t.traits().regulated());
        m.put("preorder", t.traits().preorder());
        if (t.traits().sizing() != null) {
            m.put("sizing", t.traits().sizing());
        }
        return m;
    }
}
