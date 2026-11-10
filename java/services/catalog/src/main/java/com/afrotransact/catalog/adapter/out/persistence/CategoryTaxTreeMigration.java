package com.afrotransact.catalog.adapter.out.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.adapter.out.taxonomy.ClasspathTaxonomySource;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner.Plan;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner.PlannedNode;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
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
class CategoryTaxTreeMigration implements JavaMigration {

    /** The reason V19 gives the nodes of the initial tree it keeps because something still uses them. */
    private static final String RETIRED_REASON = "Replaced by the category tax tree";
    /** Appended to the code of a retained node of the initial tree, so no code of the new tree can meet it. */
    private static final String LEGACY_SUFFIX = "-legacy";
    /** The product types of the initial tree (V2); the ones V19 keeps because products use them get the new definition. */
    private static final List<String> INITIAL_TYPES = List.of("beauty", "book", "dress", "fabric", "grocery", "hair",
            "headwrap", "home", "kids", "menwear", "perishable", "top");
    /** The nodes V2 seeded (V19 deleted the unused ones), by the deterministic id V2 gave them. */
    private static final List<String> INITIAL_NODES = List.of(
            "9155c8ee-935b-54b5-9f67-a7b03a9f7479",
            "eff7caa5-6d96-51f3-8018-a77d12dcd892",
            "d3e73236-1787-5ad2-aecd-abd23d45d2a5",
            "1fe82a7e-dc48-5fd2-afd2-e2b729fc0c74",
            "70083e54-0c5c-52f3-a15b-759c236ebc09",
            "02e2a338-9ffe-5a15-9ae6-1c867c5a4a4d",
            "2d6e0887-2b1b-59e7-9abf-01fb87ce87ae",
            "208165be-3b89-5583-a1c3-2d5a84be2417",
            "993e6383-7c7d-53f0-88f3-0d0fe8cceb2a",
            "dcd9e156-dfbe-5510-bc6f-0194418e4c9e",
            "95226236-1ee0-52b3-a581-7a8e9424acac",
            "0ae4c60a-10dd-5bf5-b077-7d90097fc8b1",
            "600103ee-be30-5bfa-a4a0-4217e9deaacb",
            "63c43edf-56eb-5289-ac7e-af9b99ed861c",
            "b263f453-e2a5-53f4-8da1-12a6f59d8e86",
            "2e553076-a36c-5f1a-8e08-272dd793034f",
            "b426ecdf-a6cb-5e8f-b8be-6e61e5ccb9ed",
            "45c911da-1c27-5955-a7fb-4b6e6b4e2c80",
            "8adab897-c661-51c6-bb5b-022004835eb0",
            "ebebb06c-286d-5c26-ae04-71f7903419b9",
            "a886116a-41d6-5b45-94fa-a1cbe2e8fdbb",
            "93fe1d67-c683-5b5b-8dd4-4aded3b8651f",
            "0ed5bd57-e153-5d12-a619-b02931f2fe31",
            "ffea101f-c4d9-5a3d-8844-702a2fc5f2dc",
            "5b477506-68fb-5584-aba1-0d137aa8e62e",
            "90ba271e-7b9d-536c-9f6c-76364e5bc815",
            "e64bf3c4-8af3-56d7-a269-552320bd9171",
            "fb5368ba-961f-50aa-9d69-10c2870f91a6",
            "e4cdfb97-85b4-5135-a726-50f2e76cac8b",
            "adc666f2-e3bd-5495-a669-f31d64177cb3",
            "7f782b5c-a26c-5aec-aa42-9ac5132fff68",
            "83012442-2af9-5032-8dc3-900a50343704",
            "726e0484-3185-53b8-a07a-150ed4ded782",
            "c433bd1b-463b-5b18-8da2-50ce3e1858f5",
            "04ad7ef3-989a-5e0f-9f0f-5581de8403e0",
            "128b10a8-593b-50c3-9963-615a81b428ef",
            "95372ce7-cf16-5e19-9a37-0c0c33b4995e",
            "9eaa88fa-3912-5a25-a70c-e45adfbb9c6a",
            "cd188866-a55b-5f9d-8d16-de618904c301",
            "f6c142b3-e007-563d-889e-56b9373dc7eb",
            "08390df5-f9af-5c18-abf1-ec2d785bb1de",
            "409399e7-ca9f-5765-a8d0-2be41d600d86",
            "ba6ae5d6-5e9b-5d93-9a19-c7ccb8d07cf8",
            "a5d48106-da2f-528b-a4a4-ec8a1f36d5ff",
            "38ff0060-313a-5ab7-9cd2-ea3f1a6d55f4",
            "453f9e9e-b442-507c-aea9-3ec7764f0da1",
            "5e673de7-2cd0-5dfc-8a09-e5f9aeb29b0e",
            "67761b06-a83e-5e91-8c32-24a365b9a429",
            "9a2b6f81-be5f-5589-92c5-b982ff249617");

    private static final String INSERT_TYPE = """
            insert into product_type (code, label, identifier_rule, attributes, themes, traits, version, updated_at)
            values (?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), 0, now())
            on conflict (code) do update
               set label = excluded.label, identifier_rule = excluded.identifier_rule, attributes = excluded.attributes,
                   themes = excluded.themes, traits = excluded.traits, version = product_type.version + 1,
                   updated_at = now()
             where product_type.code = any (?)
            """;
    /** No conflict target: a node that meets its code or its sibling name is skipped and then looked up by code. */
    private static final String INSERT_NODE = """
            insert into category (id, parent_id, code, name, level, product_type_code, path, position, active, version,
                                  updated_at, compliance, tax_profile_id, tax_status)
            values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?, true, 0, now(), ?, ?, ?)
            on conflict do nothing
            """;

    /**
     * Registered as a Spring bean (Flyway runs it as V20). It implements {@link JavaMigration} directly instead of
     * extending {@code BaseJavaMigration}, whose constructor refuses class names that do not start with V or R.
     */
    @Override
    public MigrationVersion getVersion() {
        return MigrationVersion.fromVersion("20");
    }

    @Override
    public Integer getChecksum() {
        return null;
    }

    @Override
    public boolean canExecuteInTransaction() {
        return true;
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
        retireInitialNodes(c);
        insertTypes(c, plan, json);
        insertNodes(c, plan);
        try (Statement st = c.createStatement()) {
            // Starter brands keep where they apply (V19 recorded the scopes of the retired nodes).
            try (ResultSet rs = st.executeQuery("select to_regclass('legacy_brand_scope') is not null")) {
                rs.next();
                if (rs.getBoolean(1)) {
                    st.execute("""
                            insert into brand_scope (brand_id, node_id)
                            select distinct l.brand_id, c.id from legacy_brand_scope l join category c on c.code = l.target_code
                            on conflict do nothing
                            """);
                    st.execute("drop table legacy_brand_scope");
                }
            }
        }
    }

    /**
     * What V19 kept of the initial tree (nodes that products, size charts or requests still use) is retired for good and
     * gets a code of its own ({@code <code>-legacy}), so that a code of the new tree never meets it. Without this the
     * new Health &amp; Wellness department ({@code health-wellness}) was skipped because the initial category "Health &gt;
     * Wellness" derived the same code, and its categories then failed on the parent foreign key. Names and paths stay as they
     * were: the products on these nodes keep showing the category they were listed in. Safe to run again, on a database
     * V19 left however it was (a database that recorded an earlier version of V19 included).
     */
    private static void retireInitialNodes(Connection c) throws SQLException {
        UUID[] ids = INITIAL_NODES.stream().map(UUID::fromString).toArray(UUID[]::new);
        try (PreparedStatement retire = c.prepareStatement("""
                update category set active = false, retired_at = coalesce(retired_at, now()),
                       retired_reason = coalesce(retired_reason, ?), updated_at = now(), version = version + 1
                 where id = any (?) and active
                """);
             PreparedStatement rename = c.prepareStatement("""
                update category n set code = left(n.code, 120 - length(?)) || ?, updated_at = now(), version = n.version + 1
                 where (n.id = any (?) or n.retired_reason = ?) and n.code not like '%' || ?
                   and not exists (select 1 from category o where o.code = left(n.code, 120 - length(?)) || ?)
                """)) {
            retire.setString(1, RETIRED_REASON);
            retire.setArray(2, c.createArrayOf("uuid", ids));
            retire.executeUpdate();
            rename.setString(1, LEGACY_SUFFIX);
            rename.setString(2, LEGACY_SUFFIX);
            rename.setArray(3, c.createArrayOf("uuid", ids));
            rename.setString(4, RETIRED_REASON);
            rename.setString(5, LEGACY_SUFFIX);
            rename.setString(6, LEGACY_SUFFIX);
            rename.setString(7, LEGACY_SUFFIX);
            rename.executeUpdate();
        }
    }

    private static void insertTypes(Connection c, Plan plan, JsonMapper json) throws SQLException {
        try (PreparedStatement types = c.prepareStatement(INSERT_TYPE)) {
            for (ProductType t : plan.types()) {
                types.setString(1, t.code());
                types.setString(2, t.label());
                types.setString(3, t.identifierRule().name());
                types.setString(4, json.writeValueAsString(t.attributes()));
                types.setString(5, json.writeValueAsString(t.themes()));
                types.setString(6, json.writeValueAsString(traits(t)));
                types.setArray(7, c.createArrayOf("text", INITIAL_TYPES.toArray()));
                types.addBatch();
            }
            types.executeBatch();
        }
    }

    /**
     * Inserts the planned nodes, parents first. A node is never inserted without its parent: the parent's real id is
     * looked up by code (it is the planned one unless a node with that code existed already) and a parent that cannot be
     * found stops the migration with the code in the message, instead of an opaque foreign key error.
     */
    private static void insertNodes(Connection c, Plan plan) throws SQLException {
        Map<UUID, String> codeOfPlanned = new HashMap<>();
        plan.nodes().forEach(n -> codeOfPlanned.put(n.id(), n.code()));
        Map<String, String> idOfCode = new HashMap<>();
        try (PreparedStatement insert = c.prepareStatement(INSERT_NODE);
             PreparedStatement find = c.prepareStatement("select id::text, level from category where code = ?")) {
            for (CategoryLevel level : List.of(CategoryLevel.DEPARTMENT, CategoryLevel.CATEGORY, CategoryLevel.PRODUCT_TYPE)) {
                for (PlannedNode n : plan.nodes()) {
                    if (n.level() != level) {
                        continue;
                    }
                    String parent = null;
                    if (n.parentId() != null) {
                        parent = idOfCode.get(codeOfPlanned.get(n.parentId()));
                        if (parent == null) {
                            throw new IllegalStateException("Cannot load the category tree: the parent '"
                                    + codeOfPlanned.get(n.parentId()) + "' of '" + n.code() + "' (" + n.path()
                                    + ") was not created and no category has that code");
                        }
                    }
                    insert.setString(1, n.id().toString());
                    insert.setString(2, parent);
                    insert.setString(3, n.code());
                    insert.setString(4, n.name());
                    insert.setString(5, n.level().name());
                    insert.setString(6, n.productTypeCode());
                    insert.setString(7, n.path());
                    insert.setInt(8, n.position());
                    insert.setString(9, n.compliance().isEmpty() ? null : String.join(",", n.compliance()));
                    insert.setString(10, n.taxProfileId());
                    insert.setString(11, n.taxStatus());
                    insert.executeUpdate();
                    find.setString(1, n.code());
                    try (ResultSet rs = find.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("Cannot load the category tree: '" + n.code() + "' ("
                                    + n.path() + ") was not created and no category has that code: another category "
                                    + "under the same parent already has the name '" + n.name() + "'");
                        }
                        if (!rs.getString(2).equals(n.level().name())) {
                            throw new IllegalStateException("Cannot load the category tree: a category with the code '"
                                    + n.code() + "' exists as a " + rs.getString(2) + ", the tree needs a "
                                    + n.level() + " (" + n.path() + ")");
                        }
                        idOfCode.put(n.code(), rs.getString(1));
                    }
                }
            }
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
