package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.catalog.domain.AttributeDefinition;
import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.taxonomy.AuditSubject;
import com.afrotransact.catalog.domain.taxonomy.StoredProductType;
import com.afrotransact.catalog.domain.taxonomy.TaxonomyAuditEntry;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyDataset;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner.Plan;
import com.afrotransact.catalog.domain.taxonomy.imports.TaxonomyPlanner.PlannedNode;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.TaxonomyAuditRepository;
import com.afrotransact.catalog.port.TaxonomySource;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TaxonomyImportServiceImpl implements TaxonomyImportService {

    static final String SYSTEM_ACTOR = "system:taxonomy-import";
    private static final int MAX_LISTED = 300;

    private final CategoryRepository categories;
    private final TaxonomyAuditRepository audit;
    private final TaxonomySource source;
    private final PermissionChecker permissions;
    private final DomainEventPublisher events;
    private final Clock clock;

    TaxonomyImportServiceImpl(CategoryRepository categories, TaxonomyAuditRepository audit, TaxonomySource source,
                              PermissionChecker permissions, DomainEventPublisher events, Clock clock) {
        this.categories = categories;
        this.audit = audit;
        this.source = source;
        this.permissions = permissions;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Report run(Map<String, Object> document, boolean dryRun, boolean force, String reason) {
        Actor actor = permissions.require(CatalogPermissions.TAXONOMY);
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 3) {
            throw new CatalogRuleViolation("A reason is needed", Map.of("reason", "At least 3 characters"));
        }
        TaxonomyDataset dataset = document == null ? source.bundled() : source.parse(document);
        return execute(actor.subject(), dataset, dryRun, force, why);
    }

    @Override
    @Transactional
    public Report runAsSystem(boolean force, String reason) {
        return execute(SYSTEM_ACTOR, source.bundled(), false, force, reason);
    }

    private Report execute(String actorId, TaxonomyDataset dataset, boolean dryRun, boolean force, String reason) {
        Plan plan = TaxonomyPlanner.plan(dataset, source.schemas());
        List<String> notes = new ArrayList<>();
        List<String> createdCodes = new ArrayList<>();
        Map<UUID, UUID> actualId = new HashMap<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        for (PlannedNode n : plan.nodes()) {
            UUID parent = n.parentId() == null ? null : actualId.getOrDefault(n.parentId(), n.parentId());
            Optional<CategoryNode> existing = categories.findByCode(n.code());
            if (existing.isEmpty()) {
                created++;
                createdCodes.add(n.code());
                actualId.put(n.id(), n.id());
                if (!dryRun) {
                    categories.insert(new CategoryNode(n.id(), parent, n.code(), n.name(), n.level(),
                            n.productTypeCode(), n.path(), n.position(), true, 0, null, null, n.compliance(),
                            n.taxProfileId(), n.taxStatus()));
                }
                continue;
            }
            CategoryNode current = existing.get();
            actualId.put(n.id(), current.id());
            CategoryNode next = reconcile(current, n, parent, force, notes);
            if (next.equals(current)) {
                unchanged++;
            } else {
                updated++;
                if (!dryRun) {
                    categories.update(next);
                }
            }
        }
        int createdTypes = 0;
        int updatedTypes = 0;
        for (ProductType planned : plan.types()) {
            Optional<StoredProductType> stored = categories.findStoredProductType(planned.code());
            if (stored.isEmpty()) {
                createdTypes++;
                if (!dryRun) {
                    categories.insertProductType(planned);
                }
                continue;
            }
            ProductType merged = mergeType(stored.get().type(), planned, force);
            if (!merged.equals(stored.get().type())) {
                updatedTypes++;
                if (!dryRun) {
                    categories.updateProductType(merged, stored.get().version());
                }
            }
        }
        Report report = new Report(plan.ruleVersion(), dryRun, force, (int) plan.count(CategoryLevel.DEPARTMENT),
                (int) plan.count(CategoryLevel.CATEGORY), plan.types().size(), created, updated, unchanged,
                createdTypes, updatedTypes, List.copyOf(notes));
        if (!dryRun && (created + updated + createdTypes + updatedTypes) > 0) {
            record(actorId, report, reason, createdCodes);
        }
        return report;
    }

    /** What an existing node takes from the plan: the tax profile always, the rest only when forced. */
    private static CategoryNode reconcile(CategoryNode current, PlannedNode planned, UUID parent, boolean force,
                                          List<String> notes) {
        CategoryNode next = current;
        if (planned.level() == CategoryLevel.CATEGORY) {
            String profile = planned.taxProfileId() == null ? current.taxProfileId() : planned.taxProfileId();
            String status = force || current.taxStatus() == null ? planned.taxStatus() : current.taxStatus();
            if (!Objects.equals(profile, current.taxProfileId())
                    || !Objects.equals(status, current.taxStatus())) {
                next = next.withTax(profile, status);
            }
            if (!force && planned.taxStatus() != null && current.taxStatus() != null
                    && !planned.taxStatus().equals(current.taxStatus())) {
                notes.add(planned.code() + ": status " + current.taxStatus() + " kept (dataset says "
                        + planned.taxStatus() + "); the tax screens own it");
            }
        }
        if (force) {
            if (!next.name().equals(planned.name()) || !next.path().equals(planned.path())) {
                next = next.withName(planned.name(), planned.path());
            }
            if (next.position() != planned.position() || !Objects.equals(next.parentId(), parent)) {
                next = next.withPlacement(parent, next.path(), planned.position());
            }
            if (!next.active()) {
                next = next.restored();
            }
        } else if (!current.active()) {
            notes.add(planned.code() + ": retired by staff, left retired");
        }
        return next;
    }

    /** Tax questions follow the dataset; detail questions, themes and traits are staff's unless forced. */
    private static ProductType mergeType(ProductType stored, ProductType planned, boolean force) {
        if (force) {
            return planned;
        }
        List<AttributeDefinition> attributes = new ArrayList<>(stored.attributes().stream().filter(a -> !a.taxFact()).toList());
        attributes.addAll(planned.taxFacts());
        return new ProductType(stored.code(), stored.label(), attributes, stored.themes(), stored.identifierRule(),
                stored.traits());
    }

    private void record(String actorId, Report r, String reason, List<String> createdCodes) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ruleVersion", r.ruleVersion());
        details.put("force", r.force());
        details.put("createdNodes", r.createdNodes());
        details.put("updatedNodes", r.updatedNodes());
        details.put("createdTypes", r.createdTypes());
        details.put("updatedTypes", r.updatedTypes());
        details.put("created", createdCodes.size() > MAX_LISTED ? createdCodes.subList(0, MAX_LISTED) : createdCodes);
        audit.append(new TaxonomyAuditEntry(UUID.randomUUID(), Instant.now(clock), actorId, "TAXONOMY_IMPORTED",
                AuditSubject.NODE, "taxonomy-import", "Category tax tree import (" + r.ruleVersion() + ")",
                reason == null || reason.isBlank() ? "Import of the category tax tree" : reason, details));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", "TAXONOMY_IMPORTED");
        payload.put("subjectKind", AuditSubject.NODE.name());
        payload.put("subjectId", "taxonomy-import");
        payload.put("label", "Category tax tree import");
        payload.put("version", 0L);
        events.publish(CatalogEvents.TAXONOMY_CHANGED, CatalogEvents.AGGREGATE_TAXONOMY, "taxonomy-import", payload);
    }
}
