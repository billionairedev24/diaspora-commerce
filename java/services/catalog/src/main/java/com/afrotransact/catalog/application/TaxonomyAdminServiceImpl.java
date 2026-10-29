package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.CategoryRequestStatus;
import com.afrotransact.catalog.domain.IdentifierRule;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.ProductTypeTraits;
import com.afrotransact.catalog.domain.StaleVersionException;
import com.afrotransact.catalog.domain.taxonomy.AuditSubject;
import com.afrotransact.catalog.domain.taxonomy.StoredProductType;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyAuditEntry;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyRules;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.CategoryRequestRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.TaxonomyAuditRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxonomyAdminServiceImpl implements TaxonomyAdminService {

    /** Rejected listings never went live, so they do not keep a category in use. */
    private static final Set<ProductStatus> NOT_IN_USE = Set.of(ProductStatus.REJECTED);
    private static final int MAX_AUDIT = 200;

    private final CategoryRepository categories;
    private final CategoryRequestRepository requests;
    private final ProductRepository products;
    private final TaxonomyAuditRepository audit;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final Clock clock;

    TaxonomyAdminServiceImpl(CategoryRepository categories, CategoryRequestRepository requests,
                             ProductRepository products, TaxonomyAuditRepository audit,
                             PermissionChecker permissions, DomainEventPublisher events, Clock clock) {
        this.categories = categories;
        this.requests = requests;
        this.products = products;
        this.audit = audit;
        this.permissions = permissions;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    @Override
    @Transactional(readOnly = true)
    public TaxonomyTree tree() {
        permissions.require(CatalogPermissions.TAXONOMY);
        List<CategoryNode> all = categories.findAll();
        Counts counts = counts(all);
        List<CategoryNodeView> views = all.stream().map(counts::view).toList();
        long pending = requests.findByStatus(CategoryRequestStatus.PENDING_REVIEW).size();
        return new TaxonomyTree(views, pending);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductTypeView> productTypes() {
        permissions.require(CatalogPermissions.TAXONOMY);
        List<CategoryNode> all = categories.findAll();
        Map<String, Long> counts = products.countByProductType();
        return categories.findAllStoredProductTypes().stream().map(t -> typeView(t, all, counts)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductTypeView productType(String code) {
        permissions.require(CatalogPermissions.TAXONOMY);
        return typeView(storedType(code), categories.findAll(), products.countByProductType());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxonomyAuditEntry> audit(String subjectId, int limit) {
        permissions.require(CatalogPermissions.TAXONOMY);
        return audit.find(subjectId == null || subjectId.isBlank() ? null : subjectId.trim(),
                Math.max(1, Math.min(limit, MAX_AUDIT)));
    }

    // ------------------------------------------------------------------ nodes

    @Override
    @Transactional
    public CategoryNodeView createNode(CreateNode c) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String reason = TaxonomyRules.reason(c.reason());
        String name = TaxonomyRules.name(c.name(), "name");
        if (c.level() == null) {
            throw violation("level", "Choose department, category or product type");
        }
        CategoryNode parent = parentFor(c.level(), c.parentId());
        UUID parentId = parent == null ? null : parent.id();
        List<CategoryNode> siblings = categories.findChildren(parentId);
        ensureNameFree(siblings, name, null);
        String code = codeFor(c.code(), parent, name);
        String typeCode = null;
        Map<String, Object> details = new LinkedHashMap<>();
        if (c.level() == CategoryLevel.PRODUCT_TYPE) {
            typeCode = typeFor(c, actor, reason, details);
        } else if (c.productTypeCode() != null || c.newProductType() != null) {
            throw violation("productType", "Only product-type nodes have a product type");
        }
        int position = siblings.stream().mapToInt(CategoryNode::position).max().orElse(-1) + 1;
        String path = TaxonomyRules.pathOf(parent == null ? null : parent.path(), name);
        CategoryNode node = categories.insert(new CategoryNode(UUID.randomUUID(), parentId, code, name, c.level(),
                typeCode, path, position, true, 0, null, null));
        details.put("level", node.level().name());
        details.put("code", node.code());
        if (typeCode != null) {
            details.put("productType", typeCode);
        }
        writeAudit(actor, "NODE_CREATED", AuditSubject.NODE, node.id().toString(), node.path(), reason, details);
        changed("NODE_CREATED", AuditSubject.NODE, node.id().toString(), node.path(), node.version());
        return view(node);
    }

    @Override
    @Transactional
    public CategoryNodeView renameNode(UUID id, Long expectedVersion, String newName, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(reason);
        String name = TaxonomyRules.name(newName, "name");
        CategoryNode node = lockedNode(id, expectedVersion);
        if (name.equals(node.name())) {
            throw nothingToChange("name");
        }
        ensureNameFree(categories.findChildren(node.parentId()), name, node.id());
        String oldPath = node.path();
        String parentPath = oldPath.substring(0, oldPath.length() - node.name().length());
        String newPath = parentPath + name;
        CategoryNode saved = categories.update(node.withName(name, newPath));
        rebasePaths(saved, oldPath, newPath);
        writeAudit(actor, "NODE_RENAMED", AuditSubject.NODE, saved.id().toString(), newPath, why,
                Map.of("from", node.name(), "to", name, "oldPath", oldPath));
        changed("NODE_RENAMED", AuditSubject.NODE, saved.id().toString(), newPath, saved.version());
        return view(saved);
    }

    @Override
    @Transactional
    public CategoryNodeView moveNode(UUID id, Long expectedVersion, UUID newParentId, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(reason);
        CategoryNode node = lockedNode(id, expectedVersion);
        if (node.level() == CategoryLevel.DEPARTMENT) {
            throw violation("parentId", "Departments are top level; reorder them instead");
        }
        if (newParentId == null) {
            throw violation("parentId", "Choose where it goes");
        }
        if (newParentId.equals(node.parentId())) {
            throw nothingToChange("parentId");
        }
        CategoryNode parent = parentFor(node.level(), newParentId);
        if (parent.id().equals(node.id()) || isUnder(parent, node)) {
            throw violation("parentId", "It cannot go inside itself");
        }
        ensureNameFree(categories.findChildren(parent.id()), node.name(), node.id());
        int position = categories.findChildren(parent.id()).stream().mapToInt(CategoryNode::position).max().orElse(-1) + 1;
        String oldPath = node.path();
        String newPath = TaxonomyRules.pathOf(parent.path(), node.name());
        CategoryNode saved = categories.update(node.withPlacement(parent.id(), newPath, position));
        rebasePaths(saved, oldPath, newPath);
        writeAudit(actor, "NODE_MOVED", AuditSubject.NODE, saved.id().toString(), newPath, why,
                Map.of("fromPath", oldPath, "toPath", newPath));
        changed("NODE_MOVED", AuditSubject.NODE, saved.id().toString(), newPath, saved.version());
        return view(saved);
    }

    @Override
    @Transactional
    public CategoryNodeView retireNode(UUID id, Long expectedVersion, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(reason);
        CategoryNode node = lockedNode(id, expectedVersion);
        if (!node.active()) {
            throw nothingToChange("form");
        }
        List<CategoryNode> subtree = subtreeOf(node, categories.findAll());
        Map<UUID, Long> counts = products.countByCategory(NOT_IN_USE);
        long inUse = subtree.stream().mapToLong(n -> counts.getOrDefault(n.id(), 0L)).sum();
        if (inUse > 0) {
            throw new ConflictException("catalog.taxonomy-in-use", node.path() + " still has " + inUse
                    + (inUse == 1 ? " product" : " products") + ". Move its product types to another category first;"
                    + " a category with products is never retired.");
        }
        Instant now = Instant.now(clock);
        CategoryNode saved = node;
        for (CategoryNode n : subtree) {
            if (n.active()) {
                CategoryNode done = categories.update(n.retired(why, now));
                if (n.id().equals(node.id())) {
                    saved = done;
                }
            }
        }
        writeAudit(actor, "NODE_RETIRED", AuditSubject.NODE, saved.id().toString(), saved.path(), why,
                Map.of("retiredNodes", subtree.stream().filter(CategoryNode::active).count()));
        changed("NODE_RETIRED", AuditSubject.NODE, saved.id().toString(), saved.path(), saved.version());
        return view(saved);
    }

    @Override
    @Transactional
    public CategoryNodeView restoreNode(UUID id, Long expectedVersion, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(reason);
        CategoryNode node = lockedNode(id, expectedVersion);
        if (node.active()) {
            throw nothingToChange("form");
        }
        if (node.parentId() != null && !categories.findById(node.parentId()).map(CategoryNode::active).orElse(false)) {
            throw violation("form", "Restore " + parentName(node) + " first");
        }
        Instant retiredAt = node.retiredAt();
        CategoryNode saved = node;
        int restored = 0;
        for (CategoryNode n : subtreeOf(node, categories.findAll())) {
            boolean withNode = n.id().equals(node.id()) || (!n.active() && retiredAt != null
                    && retiredAt.equals(n.retiredAt()));
            if (withNode) {
                CategoryNode done = categories.update(n.restored());
                restored++;
                if (n.id().equals(node.id())) {
                    saved = done;
                }
            }
        }
        writeAudit(actor, "NODE_RESTORED", AuditSubject.NODE, saved.id().toString(), saved.path(), why,
                Map.of("restoredNodes", restored));
        changed("NODE_RESTORED", AuditSubject.NODE, saved.id().toString(), saved.path(), saved.version());
        return view(saved);
    }

    @Override
    @Transactional
    public List<CategoryNodeView> reorder(UUID parentId, List<UUID> orderedIds, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(reason);
        CategoryNode parent = null;
        if (parentId != null) {
            parent = categories.findById(parentId).orElseThrow(() -> new NotFoundException(
                    "catalog.category-not-found", "Category not found"));
        }
        List<CategoryNode> siblings = categories.findChildren(parentId);
        if (orderedIds == null || orderedIds.size() != new HashSet<>(orderedIds).size()
                || !new HashSet<>(orderedIds).equals(siblings.stream().map(CategoryNode::id).collect(
                        Collectors.toSet()))) {
            throw new StaleVersionException("The list changed since you opened it. Reload and arrange it again.");
        }
        Map<UUID, CategoryNode> byId = new HashMap<>();
        siblings.forEach(s -> byId.put(s.id(), s));
        boolean moved = false;
        List<CategoryNodeView> out = new ArrayList<>();
        for (int i = 0; i < orderedIds.size(); i++) {
            CategoryNode n = byId.get(orderedIds.get(i));
            CategoryNode kept = n.position() == i ? n : categories.update(n.withPosition(i));
            moved |= n.position() != i;
            out.add(view(kept));
        }
        if (!moved) {
            throw nothingToChange("form");
        }
        String label = parent == null ? "Departments" : parent.path();
        List<String> order = orderedIds.stream().map(i -> byId.get(i).name()).toList();
        writeAudit(actor, "NODES_REORDERED", AuditSubject.NODE, parentId == null ? "root" : parentId.toString(), label,
                why, Map.of("order", order));
        changed("NODES_REORDERED", AuditSubject.NODE, parentId == null ? "root" : parentId.toString(), label, 0);
        return out;
    }

    // ------------------------------------------------------------------ product types

    @Override
    @Transactional
    public ProductTypeView createProductType(NewProductType command, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(reason);
        StoredProductType created = insertType(command, actor, why, new LinkedHashMap<>());
        return typeView(created, categories.findAll(), products.countByProductType());
    }

    @Override
    @Transactional
    public ProductTypeView updateProductType(String code, Long expectedVersion, ProductTypeChange change) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = TaxonomyRules.reason(change.reason());
        StoredProductType stored = storedType(code);
        if (ProductType.CUSTOM.equals(code)) {
            throw violation("form", "The placeholder type used while a category is requested cannot be edited");
        }
        Versions.check(expectedVersion, stored.version(), "product type");
        ProductType before = stored.type();
        String label = change.label() == null ? before.label() : TaxonomyRules.name(change.label(), "label");
        IdentifierRule rule = change.identifierRule() == null ? before.identifierRule() : change.identifierRule();
        ProductTypeTraits traits = TaxonomyRules.traits(change.traits() == null ? before.traits() : change.traits());
        List<AttributeDefinition> attributes = change.attributes() == null ? before.attributes()
                : TaxonomyRules.attributes(change.attributes(), traits, before.attributes());
        if (change.attributes() == null) {
            // Traits may have changed under attributes that stay as they are.
            TaxonomyRules.attributes(before.attributes(), traits, before.attributes());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (!label.equals(before.label())) {
            details.put("label", Map.of("from", before.label(), "to", label));
        }
        if (rule != before.identifierRule()) {
            details.put("identifierRule", Map.of("from", before.identifierRule().name(), "to", rule.name()));
        }
        if (!traits.equals(before.traits())) {
            details.put("traits", Map.of("from", traitNames(before.traits()), "to", traitNames(traits)));
        }
        Map<String, List<String>> attributeChanges = attributeChanges(before.attributes(), attributes);
        if (!attributeChanges.isEmpty()) {
            details.put("attributes", attributeChanges);
        }
        if (details.isEmpty()) {
            throw nothingToChange("form");
        }
        ProductType next = new ProductType(code, label, attributes, before.themes(), rule, traits);
        StoredProductType saved = categories.updateProductType(next, stored.version());
        writeAudit(actor, "TYPE_UPDATED", AuditSubject.PRODUCT_TYPE, code, label, why, details);
        changed("TYPE_UPDATED", AuditSubject.PRODUCT_TYPE, code, label, saved.version());
        return typeView(saved, categories.findAll(), products.countByProductType());
    }

    // ------------------------------------------------------------------ helpers

    private StoredProductType insertType(NewProductType n, Actor actor, String reason, Map<String, Object> nodeDetails) {
        String label = TaxonomyRules.name(n.label(), "label");
        String code = n.code() == null || n.code().isBlank() ? freeTypeCode(TaxonomyRules.typeCodeFrom(label))
                : TaxonomyRules.typeCode(n.code());
        if (categories.findProductType(code).isPresent()) {
            throw violation("code", "Already used by another product type");
        }
        if (n.identifierRule() == null) {
            throw violation("identifierRule", "Choose GTIN or ISBN");
        }
        ProductTypeTraits traits = TaxonomyRules.traits(n.traits());
        List<AttributeDefinition> attributes = TaxonomyRules.attributes(
                n.attributes() == null ? List.of() : n.attributes(), traits, null);
        String basedOn = n.basedOn() == null || n.basedOn().isBlank() ? ProductType.CUSTOM : n.basedOn().trim();
        ProductType template = categories.findProductType(basedOn).orElseThrow(
                () -> violation("basedOn", "Unknown product type " + basedOn));
        StoredProductType saved = categories.insertProductType(
                new ProductType(code, label, attributes, template.themes(), n.identifierRule(), traits));
        writeAudit(actor, "TYPE_CREATED", AuditSubject.PRODUCT_TYPE, code, label, reason,
                Map.of("identifierRule", n.identifierRule().name(), "attributes", attributes.size(),
                        "variationsFrom", basedOn));
        changed("TYPE_CREATED", AuditSubject.PRODUCT_TYPE, code, label, saved.version());
        nodeDetails.put("newProductType", code);
        return saved;
    }

    private String typeFor(CreateNode c, Actor actor, String reason, Map<String, Object> details) {
        boolean existing = c.productTypeCode() != null && !c.productTypeCode().isBlank();
        if (existing == (c.newProductType() != null)) {
            throw violation("productType", "Choose an existing product type or define a new one");
        }
        if (c.newProductType() != null) {
            return insertType(c.newProductType(), actor, reason, details).type().code();
        }
        String code = c.productTypeCode().trim();
        if (ProductType.CUSTOM.equals(code) || categories.findProductType(code).isEmpty()) {
            throw violation("productType", "Unknown product type");
        }
        return code;
    }

    private String freeTypeCode(String base) {
        String code = base;
        for (int i = 2; categories.findProductType(code).isPresent(); i++) {
            String suffix = "_" + i;
            code = (base.length() + suffix.length() > 40 ? base.substring(0, 40 - suffix.length()) : base) + suffix;
        }
        return code;
    }

    private CategoryNode parentFor(CategoryLevel level, UUID parentId) {
        CategoryLevel expected = TaxonomyRules.parentLevel(level);
        if (expected == null) {
            if (parentId != null) {
                throw violation("parentId", "Departments have no parent");
            }
            return null;
        }
        if (parentId == null) {
            throw violation("parentId", "Choose where it goes");
        }
        CategoryNode parent = categories.findById(parentId).orElseThrow(
                () -> new NotFoundException("catalog.category-not-found", "Parent not found"));
        if (parent.level() != expected) {
            throw violation("parentId", level == CategoryLevel.CATEGORY ? "A category goes under a department"
                    : "A product type goes under a category");
        }
        if (!parent.active()) {
            throw violation("parentId", parent.name() + " is retired");
        }
        return parent;
    }

    private String codeFor(String requested, CategoryNode parent, String name) {
        if (requested != null && !requested.isBlank()) {
            String code = TaxonomyRules.nodeCode(requested);
            if (categories.findByCode(code).isPresent()) {
                throw violation("code", "Already used by another category");
            }
            return code;
        }
        String base = TaxonomyRules.slug((parent == null ? "" : parent.code() + "-") + name);
        if (base.isEmpty()) {
            throw violation("name", "Use letters or digits in the name");
        }
        base = base.length() > 110 ? base.substring(0, 110).replaceAll("-+$", "") : base;
        String code = base;
        for (int i = 2; categories.findByCode(code).isPresent(); i++) {
            code = base + "-" + i;
        }
        return code;
    }

    private void ensureNameFree(List<CategoryNode> siblings, String name, UUID except) {
        boolean taken = siblings.stream().anyMatch(s -> !s.id().equals(except)
                && s.name().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
        if (taken) {
            throw violation("name", "There is already one called " + name + " here");
        }
    }

    private CategoryNode lockedNode(UUID id, Long expectedVersion) {
        CategoryNode node = categories.findById(id).orElseThrow(
                () -> new NotFoundException("catalog.category-not-found", "Category not found"));
        Versions.check(expectedVersion, node.version(), "category");
        return node;
    }

    private StoredProductType storedType(String code) {
        return categories.findStoredProductType(code).orElseThrow(
                () -> new NotFoundException("catalog.product-type-not-found", "Unknown product type " + code));
    }

    private String parentName(CategoryNode node) {
        return categories.findById(node.parentId()).map(CategoryNode::name).orElse("its parent");
    }

    /** Whether {@code candidate} is somewhere under {@code ancestor}. */
    private boolean isUnder(CategoryNode candidate, CategoryNode ancestor) {
        CategoryNode cursor = candidate;
        while (cursor != null && cursor.parentId() != null) {
            if (cursor.parentId().equals(ancestor.id())) {
                return true;
            }
            cursor = categories.findById(cursor.parentId()).orElse(null);
        }
        return false;
    }

    /** The node and everything under it. */
    private static List<CategoryNode> subtreeOf(CategoryNode root, List<CategoryNode> all) {
        Map<UUID, List<CategoryNode>> children = new HashMap<>();
        all.stream().filter(n -> n.parentId() != null)
                .forEach(n -> children.computeIfAbsent(n.parentId(), k -> new ArrayList<>()).add(n));
        List<CategoryNode> out = new ArrayList<>();
        List<CategoryNode> queue = new ArrayList<>(List.of(root));
        while (!queue.isEmpty()) {
            CategoryNode n = queue.removeFirst();
            out.add(n);
            queue.addAll(children.getOrDefault(n.id(), List.of()));
        }
        return out;
    }

    /** Descendants take the new path prefix; saved product-form drafts that stored the old path follow. */
    private void rebasePaths(CategoryNode node, String oldPath, String newPath) {
        for (CategoryNode n : subtreeOf(node, categories.findAll())) {
            if (!n.id().equals(node.id())) {
                categories.updatePath(n.id(), newPath + n.path().substring(oldPath.length()));
            }
        }
        categories.rewriteDraftPaths(oldPath, newPath);
    }

    private Counts counts(List<CategoryNode> all) {
        Map<UUID, Long> direct = products.countByCategory(NOT_IN_USE);
        Map<UUID, List<CategoryNode>> children = new HashMap<>();
        all.stream().filter(n -> n.parentId() != null)
                .forEach(n -> children.computeIfAbsent(n.parentId(), k -> new ArrayList<>()).add(n));
        Map<String, String> labels = new HashMap<>();
        categories.findAllProductTypes().forEach(t -> labels.put(t.code(), t.label()));
        Map<UUID, Long> subtree = new HashMap<>();
        all.stream().filter(n -> n.parentId() == null).forEach(n -> sum(n, direct, children, subtree));
        return new Counts(direct, subtree, children, labels);
    }

    private static long sum(CategoryNode n, Map<UUID, Long> direct, Map<UUID, List<CategoryNode>> children,
                            Map<UUID, Long> subtree) {
        long total = direct.getOrDefault(n.id(), 0L);
        for (CategoryNode child : children.getOrDefault(n.id(), List.of())) {
            total += sum(child, direct, children, subtree);
        }
        subtree.put(n.id(), total);
        return total;
    }

    private CategoryNodeView view(CategoryNode node) {
        return counts(categories.findAll()).view(node);
    }

    private ProductTypeView typeView(StoredProductType stored, List<CategoryNode> all, Map<String, Long> counts) {
        List<TypeUse> uses = all.stream().filter(n -> stored.type().code().equals(n.productTypeCode()))
                .map(n -> new TypeUse(n.id(), n.path(), n.active())).toList();
        return new ProductTypeView(stored, counts.getOrDefault(stored.type().code(), 0L), uses);
    }

    private static Map<String, List<String>> attributeChanges(List<AttributeDefinition> before,
                                                              List<AttributeDefinition> after) {
        Map<String, AttributeDefinition> was = new LinkedHashMap<>();
        before.forEach(a -> was.put(a.key(), a));
        Map<String, AttributeDefinition> is = new LinkedHashMap<>();
        after.forEach(a -> is.put(a.key(), a));
        List<String> added = is.keySet().stream().filter(k -> !was.containsKey(k)).toList();
        List<String> removed = was.keySet().stream().filter(k -> !is.containsKey(k)).toList();
        List<String> changed = is.keySet().stream().filter(k -> was.containsKey(k) && !same(was.get(k), is.get(k)))
                .toList();
        List<String> keptBefore = before.stream().map(AttributeDefinition::key).filter(is::containsKey).toList();
        List<String> keptAfter = after.stream().map(AttributeDefinition::key).filter(was::containsKey).toList();
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!added.isEmpty()) {
            out.put("added", added);
        }
        if (!removed.isEmpty()) {
            out.put("removed", removed);
        }
        if (!changed.isEmpty()) {
            out.put("changed", changed);
        }
        if (!keptBefore.equals(keptAfter)) {
            out.put("reordered", List.of("order"));
        }
        return out;
    }

    private static boolean same(AttributeDefinition a, AttributeDefinition b) {
        return a.label().equals(b.label()) && a.options().equals(b.options()) && a.required() == b.required()
                && a.kind() == b.kind() && a.units().equals(b.units()) && a.section() == b.section()
                && Objects.equals(a.help(), b.help()) && a.applies().equals(b.applies());
    }

    private static List<String> traitNames(ProductTypeTraits t) {
        List<String> names = new ArrayList<>();
        if (t.food()) {
            names.add("food");
        }
        if (t.perishable()) {
            names.add("perishable");
        }
        if (t.regulated()) {
            names.add("regulated");
        }
        if (t.preorder()) {
            names.add("preorder");
        }
        return names;
    }

    private static CatalogRuleViolation violation(String field, String message) {
        return new CatalogRuleViolation(message, Map.of(field, message));
    }

    private static CatalogRuleViolation nothingToChange(String field) {
        return new CatalogRuleViolation("Nothing to change", Map.of(field, "Nothing changed"));
    }

    private void writeAudit(Actor actor, String action, AuditSubject kind, String subjectId, String label, String reason,
                        Map<String, Object> details) {
        audit.append(new TaxonomyAuditEntry(UUID.randomUUID(), Instant.now(clock), actor.subject(), action, kind,
                subjectId, label, reason, details));
    }

    private void changed(String action, AuditSubject kind, String subjectId, String label, long version) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("subjectKind", kind.name());
        payload.put("subjectId", subjectId);
        payload.put("label", label);
        payload.put("version", version);
        events.publish(CatalogEvents.TAXONOMY_CHANGED, CatalogEvents.AGGREGATE_TAXONOMY, subjectId, payload);
    }

    private record Counts(Map<UUID, Long> direct, Map<UUID, Long> subtree, Map<UUID, List<CategoryNode>> children,
                          Map<String, String> typeLabels) {

        CategoryNodeView view(CategoryNode n) {
            return new CategoryNodeView(n, n.productTypeCode() == null ? null : typeLabels.get(n.productTypeCode()),
                    direct.getOrDefault(n.id(), 0L), subtree.getOrDefault(n.id(), 0L),
                    children.getOrDefault(n.id(), List.of()).size());
        }
    }
}
