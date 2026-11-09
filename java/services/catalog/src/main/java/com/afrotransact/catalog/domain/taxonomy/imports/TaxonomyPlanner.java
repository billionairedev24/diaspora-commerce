package com.afrotransact.catalog.domain.taxonomy.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyDataset.Category;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyDataset.Department;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyDataset.ProductTypeDef;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomySchemas.Provisional;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomySchemas.Schema;
import com.afrotransact.catalog.domain.tax.TaxFactRules;

/**
 * Turns the tax dataset and the schema resource into the tree to store: departments, categories (with tax profile and
 * status), one product-type leaf per product type, and the product-type schemas (details plus tax facts). Pure: the
 * same input always plans the same ids and codes, so importing twice changes nothing.
 *
 * <p>Every node has a deterministic id (name-based UUID of its code) and a stable code: the department and category
 * codes of the dataset, and for a product-type leaf {@code <category>-<type>} (or {@code <category>-item} for the
 * category's default type).
 */
public final class TaxonomyPlanner {

    public static final String PATH_SEPARATOR = " › ";
    private static final String NAMESPACE = "afrotransact.catalog.taxonomy.v2:";

    private TaxonomyPlanner() {
    }

    /** A node to store. */
    public record PlannedNode(UUID id, UUID parentId, String code, String name, CategoryLevel level,
                              String productTypeCode, String path, int position, String taxProfileId,
                              String taxStatus, Set<String> compliance, List<String> statusReasons) {
    }

    /** What an import would create or keep. */
    public record Plan(String ruleVersion, List<PlannedNode> nodes, List<ProductType> types) {

        public long count(CategoryLevel level) {
            return nodes.stream().filter(n -> n.level() == level).count();
        }
    }

    public static UUID idOf(String code) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest((NAMESPACE + code).getBytes(StandardCharsets.UTF_8));
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (hash[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (hash[i] & 0xff);
            }
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String leafCode(Category category, ProductTypeDef type) {
        return category.code() + "-" + (type.code().equals(category.code()) ? "item" : type.code());
    }

    public static Plan plan(TaxonomyDataset dataset, TaxonomySchemas schemas) {
        List<PlannedNode> nodes = new ArrayList<>();
        Map<String, ProductType> types = new LinkedHashMap<>();
        Map<String, PlannedNode> departments = new LinkedHashMap<>();
        for (Department d : dataset.departments()) {
            PlannedNode node = new PlannedNode(idOf(d.code()), null, d.code(), d.name(), CategoryLevel.DEPARTMENT, null,
                    d.name(), d.position(), null, null, schemas.complianceOf(d.code()), List.of());
            departments.put(d.code(), node);
            nodes.add(node);
        }
        Map<String, Integer> categoryCount = new LinkedHashMap<>();
        for (Category c : dataset.categories()) {
            PlannedNode dept = departments.get(c.department());
            if (dept == null) {
                throw new IllegalArgumentException("Category " + c.code() + " is in unknown department " + c.department());
            }
            List<AttributeDefinition> facts = TaxFactSchema.forCategory(dataset, c);
            PlannedNode category = category(dept, c.code(), c.name(), c.position(), c.code(), c.status(), c.statusReasons());
            nodes.add(category);
            categoryCount.merge(c.department(), 1, Integer::sum);
            addTypes(nodes, types, category, c.department(), c.code(), c.productTypes(), facts, schemas);
        }
        int extra = 0;
        for (Provisional p : schemas.provisional()) {
            PlannedNode dept = departments.get(p.department());
            int position = categoryCount.getOrDefault(p.department(), 0) + extra++;
            PlannedNode category = category(dept, p.code(), p.name(), position, null, p.status(), p.statusReasons());
            nodes.add(category);
            addTypes(nodes, types, category, p.department(), p.code(),
                    List.of(new ProductTypeDef(p.productType(), p.name(), false, true)), List.of(), schemas);
        }
        return new Plan(dataset.ruleVersion(), List.copyOf(nodes), List.copyOf(types.values()));
    }

    private static PlannedNode category(PlannedNode dept, String code, String name, int position, String profile,
                                        String status, List<String> reasons) {
        return new PlannedNode(idOf(code), dept.id(), code, name, CategoryLevel.CATEGORY, null,
                dept.path() + PATH_SEPARATOR + name, position, profile, status, Set.of(), reasons);
    }

    private static void addTypes(List<PlannedNode> nodes, Map<String, ProductType> types, PlannedNode category,
                                 String department, String categoryCode, List<ProductTypeDef> defs,
                                 List<AttributeDefinition> facts, TaxonomySchemas schemas) {
        int position = 0;
        for (ProductTypeDef t : defs) {
            Category shell = new Category(categoryCode, department, category.name(), 0, category.taxStatus(), List.of(),
                    List.of(), defs);
            String leaf = leafCode(shell, t);
            nodes.add(new PlannedNode(idOf(leaf), category.id(), leaf, t.label(), CategoryLevel.PRODUCT_TYPE, t.code(),
                    category.path() + PATH_SEPARATOR + t.label(), position++, null, null, Set.of(), List.of()));
            Schema schema = schemas.schemaFor(department, categoryCode, t.code());
            List<AttributeDefinition> attributes = new ArrayList<>(schema.attributes());
            attributes.addAll(facts);
            types.putIfAbsent(t.code(), new ProductType(t.code(), t.label(), attributes, schema.themes(),
                    schema.identifierRule(), schema.traits()));
        }
    }

    /** The variant and listing questions of a product type in one list (a question may depend on either). */
    public static List<AttributeDefinition> taxQuestions(ProductType type) {
        return type.taxFacts().stream()
                .filter(a -> TaxFactRules.OWNER_VARIANT.equals(a.owner()) || TaxFactRules.OWNER_OFFER.equals(a.owner()))
                .toList();
    }
}
