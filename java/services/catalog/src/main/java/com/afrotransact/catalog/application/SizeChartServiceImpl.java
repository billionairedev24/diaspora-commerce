package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.CustomValues;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.VariationDimension;
import com.afrotransact.catalog.domain.imports.ChartImportColumns;
import com.afrotransact.catalog.domain.imports.ChartImportContext;
import com.afrotransact.catalog.domain.imports.ChartImportResult;
import com.afrotransact.catalog.domain.imports.ChartImportValidator;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.SpreadsheetRejected;
import com.afrotransact.catalog.domain.imports.TemplateSpec;
import com.afrotransact.catalog.domain.imports.ValidatedRow;
import com.afrotransact.catalog.domain.sizing.ChartContent;
import com.afrotransact.catalog.domain.sizing.MeasurementUnit;
import com.afrotransact.catalog.domain.sizing.SizeChart;
import com.afrotransact.catalog.domain.sizing.SizeChartRules;
import com.afrotransact.catalog.domain.sizing.SizingTrait;
import com.afrotransact.catalog.domain.sizing.SuggestedMeasurement;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SizeChartRepository;
import com.afrotransact.catalog.port.SpreadsheetReader;
import com.afrotransact.catalog.port.SpreadsheetWriter;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.PrincipalType;
import com.afrotransact.platform.security.relationship.FgaObject;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SizeChartServiceImpl implements SizeChartService {

    /** Products checked when a chart's sizes change. */
    private static final int PRODUCT_CHECK_LIMIT = 1000;

    private final SizeChartRepository charts;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final OwnerResolver owners;
    private final SpreadsheetReader reader;
    private final SpreadsheetWriter writer;
    private final Clock clock;

    SizeChartServiceImpl(SizeChartRepository charts, ProductRepository products, CategoryRepository categories,
                         PermissionChecker permissions, ResourceAccessChecker access, OwnerResolver owners,
                         SpreadsheetReader reader, SpreadsheetWriter writer, Clock clock) {
        this.charts = charts;
        this.products = products;
        this.categories = categories;
        this.permissions = permissions;
        this.access = access;
        this.owners = owners;
        this.reader = reader;
        this.writer = writer;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PageView<ChartView> list(String ownerOrgId, String productTypeCode, UUID categoryId, boolean includeRetired,
                                    String text, int page, int size) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        String org = isStaff(actor) ? blankToNull(ownerOrgId) : owners.ownerFor(actor).orgId();
        if (org == null) {
            access.require(CatalogPermissions.WRITE, CatalogRelations.PLATFORM_VIEW_ALL, FgaObject.PLATFORM);
        } else {
            access.requireForOrg(CatalogPermissions.WRITE, CatalogRelations.ORG_VIEW_PRODUCTS, org);
        }
        String type = blankToNull(productTypeCode);
        if (categoryId != null) {
            type = categories.findById(categoryId).map(CategoryNode::productTypeCode).orElse(type);
        }
        SizeChartRepository.ChartPage result = charts.list(new SizeChartRepository.ChartQuery(org, type, null,
                includeRetired, text, Math.max(page, 0), Math.clamp(size, 1, 100)));
        Map<UUID, Long> used = products.countBySizeCharts(result.items().stream().map(SizeChart::id).toList());
        List<ChartView> items = result.items().stream().map(c -> new ChartView(c, used.getOrDefault(c.id(), 0L)))
                .toList();
        return new PageView<>(items, result.total(), result.page(), result.size());
    }

    @Override
    @Transactional(readOnly = true)
    public ChartView get(UUID id) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        SizeChart chart = visible(actor, id);
        access.requireForOrg(CatalogPermissions.WRITE, CatalogRelations.ORG_VIEW_PRODUCTS, chart.ownerOrg());
        return view(chart);
    }

    @Override
    @Transactional
    public ChartView create(ChartContent content) {
        ListingOwner owner = writingOwner();
        return view(insertChart(owner, placed(SizeChartRules.validate(content))));
    }

    @Override
    @Transactional
    public ChartView update(UUID id, Long expectedVersion, ChartContent content) {
        ListingOwner owner = writingOwner();
        SizeChart current = owned(owner, id);
        if (current.retired()) {
            throw new ConflictException("catalog.size-chart-retired",
                    "This size chart was retired; duplicate it to make a new one");
        }
        if (expectedVersion != null && expectedVersion != current.version()) {
            throw new ConflictException("catalog.size-chart-changed",
                    "This size chart was changed since you opened it; reload it and try again");
        }
        return view(updateChart(owner, current, placed(SizeChartRules.validate(content)), true));
    }

    @Override
    @Transactional
    public ChartView retire(UUID id) {
        ListingOwner owner = writingOwner();
        SizeChart current = owned(owner, id);
        if (current.retired()) {
            return view(current);
        }
        return view(charts.update(current.retire(Instant.now(clock))));
    }

    @Override
    @Transactional
    public ChartView duplicate(UUID id, String newName) {
        ListingOwner owner = writingOwner();
        SizeChart source = owned(owner, id);
        String name = newName == null || newName.isBlank() ? freeCopyName(owner.orgId(), source.name())
                : CustomValues.clean(newName);
        ChartContent copy = source.content();
        ChartContent valid = SizeChartRules.validate(new ChartContent(name, copy.unitSystem(), copy.productTypeCode(),
                copy.categoryId(), copy.columns(), copy.rows(), copy.fitNote(), copy.howToMeasure()));
        return view(insertChart(owner, valid));
    }

    @Override
    @Transactional(readOnly = true)
    public Suggestions suggestions(UUID categoryId, String productType) {
        permissions.require(CatalogPermissions.WRITE);
        String code = blankToNull(productType);
        if (categoryId != null) {
            CategoryNode node = categories.findById(categoryId).filter(CategoryNode::active)
                    .orElseThrow(() -> new NotFoundException("catalog.category-not-found", "Category not found"));
            if (node.level() != CategoryLevel.PRODUCT_TYPE || node.productTypeCode() == null) {
                throw new CatalogRuleViolation("Pick a product type (third level)",
                        Map.of("productTypeId", "Pick a product type (third level)"));
            }
            code = node.productTypeCode();
        }
        if (code == null) {
            throw new CatalogRuleViolation("Name the product type", Map.of("productTypeId", "Required"));
        }
        ProductType type = categories.findProductType(code)
                .orElseThrow(() -> new NotFoundException("catalog.product-type-not-found", "Unknown product type"));
        return suggestionsOf(type);
    }

    @Override
    @Transactional(readOnly = true)
    public FileContent template(String productType, UUID categoryId, SpreadsheetFormat format) {
        permissions.require(CatalogPermissions.WRITE);
        TemplateSpec spec = ChartImportColumns.template(typeOf(productType, categoryId));
        return new FileContent(spec.fileStem() + "." + format.extension(), format.contentType(),
                writer.template(spec, format));
    }

    @Override
    @Transactional
    public ImportReport importCharts(String fileName, byte[] content, String productType, UUID categoryId,
                                     boolean dryRun) {
        ListingOwner owner = writingOwner();
        SpreadsheetFormat format = SpreadsheetFormat.of(fileName);
        if (format == null) {
            throw new SpreadsheetRejected("Upload an Excel (.xlsx) or CSV (.csv) file");
        }
        ProductType type = typeOf(productType, categoryId);
        MeasurementUnit unit = type == null ? MeasurementUnit.CM : suggestionsOf(type).unitSystem();
        ChartImportResult read = ChartImportValidator.validate(reader.read(content, format),
                new ChartImportContext(type == null ? null : type.code(), unit));
        Map<String, SizeChart> existing = charts.activeByNames(owner.orgId(),
                read.charts().stream().map(ChartImportResult.ParsedChart::name).toList());
        List<ChartOutcome> outcomes = new ArrayList<>();
        Set<String> saved = new HashSet<>();
        for (ChartImportResult.ParsedChart parsed : read.charts()) {
            saved.add(CustomValues.key(parsed.name()));
            outcomes.add(importOne(owner, parsed, existing.get(CustomValues.key(parsed.name())), dryRun));
        }
        Map<String, List<Integer>> failedRows = new LinkedHashMap<>();
        Map<String, String> failedNames = new LinkedHashMap<>();
        for (ValidatedRow row : read.rows()) {
            String key = CustomValues.key(row.groupKey());
            if (!key.isEmpty() && !saved.contains(key)) {
                failedRows.computeIfAbsent(key, k -> new ArrayList<>()).add(row.rowNumber());
                failedNames.putIfAbsent(key, row.groupKey());
            }
        }
        failedRows.forEach((key, rows) -> outcomes.add(new ChartOutcome(failedNames.get(key), ImportAction.FAILED,
                null, rows, "Fix the rows with errors and upload the file again; the chart is saved whole")));
        return new ImportReport(!dryRun, fileName, read.fileWarnings(), read.chartCount(), outcomes, read.rows());
    }

    /** Creates or updates one chart of the file (or, for a dry run, finds out whether it could be). */
    private ChartOutcome importOne(ListingOwner owner, ChartImportResult.ParsedChart parsed, SizeChart current,
                                   boolean dryRun) {
        try {
            if (current == null) {
                ChartContent valid = placed(parsed.content());
                if (dryRun) {
                    requireNameFree(owner.orgId(), valid.name(), null);
                    return new ChartOutcome(parsed.name(), ImportAction.CREATE, null, parsed.rowNumbers(), null);
                }
                SizeChart created = insertChart(owner, valid);
                return new ChartOutcome(parsed.name(), ImportAction.CREATE, created.id(), parsed.rowNumbers(), null);
            }
            ChartContent kept = keepingPlacement(parsed.content(), current);
            if (SizeChartRules.same(kept, current.content())) {
                return new ChartOutcome(parsed.name(), ImportAction.UNCHANGED, current.id(), parsed.rowNumbers(), null);
            }
            SizeChart next = updateChart(owner, current, placed(kept), !dryRun);
            return new ChartOutcome(parsed.name(), ImportAction.UPDATE, next.id(), parsed.rowNumbers(), null);
        } catch (CatalogRuleViolation ex) {
            String detail = ex.fieldErrors().values().stream().findFirst().orElse(ex.getMessage());
            return new ChartOutcome(parsed.name(), ImportAction.FAILED, null, parsed.rowNumbers(), detail);
        }
    }

    /** A re-upload without a product type keeps what the chart was made for. */
    private static ChartContent keepingPlacement(ChartContent uploaded, SizeChart current) {
        String type = uploaded.productTypeCode() != null ? uploaded.productTypeCode() : current.productTypeCode();
        UUID category = Objects.equals(type, current.productTypeCode()) ? current.categoryId() : null;
        return new ChartContent(uploaded.name(), uploaded.unitSystem(), type, category, uploaded.columns(),
                uploaded.rows(), uploaded.fitNote(), uploaded.howToMeasure());
    }

    private ProductType typeOf(String productType, UUID categoryId) {
        String code = blankToNull(productType);
        if (categoryId != null) {
            CategoryNode node = categories.findById(categoryId).filter(CategoryNode::active)
                    .orElseThrow(() -> new NotFoundException("catalog.category-not-found", "Category not found"));
            if (node.productTypeCode() == null) {
                throw new NotFoundException("catalog.product-type-not-found", "Choose a product type (third level)");
            }
            code = node.productTypeCode();
        }
        if (code == null) {
            return null;
        }
        String wanted = code;
        return categories.findProductType(wanted).orElseThrow(
                () -> new NotFoundException("catalog.product-type-not-found", "Unknown product type " + wanted));
    }

    static Suggestions suggestionsOf(ProductType type) {
        Optional<SizingTrait> sizing = type.sizing();
        if (sizing.isEmpty()) {
            return new Suggestions(type.code(), type.label(), false, false, SizingTrait.DEFAULT_DIMENSION, List.of(),
                    false, MeasurementUnit.CM, List.of());
        }
        SizingTrait trait = sizing.get();
        List<String> sizes = new ArrayList<>();
        type.themes().forEach(t -> {
            VariationDimension d = t.dimension(trait.sizeDimension());
            if (d != null) {
                d.options().stream().filter(o -> !sizes.contains(o)).forEach(sizes::add);
            }
        });
        MeasurementUnit unit = trait.suggestedMeasurements().stream().map(SuggestedMeasurement::unit)
                .filter(Objects::nonNull).findFirst().orElse(MeasurementUnit.CM);
        return new Suggestions(type.code(), type.label(), true, trait.sizeChartRequired(), trait.sizeDimension(), sizes,
                true, unit, trait.suggestedMeasurements());
    }

    // ------------------------------------------------------------------ helpers

    private SizeChart insertChart(ListingOwner owner, ChartContent valid) {
        requireNameFree(owner.orgId(), valid.name(), null);
        Instant now = Instant.now(clock);
        return insert(new SizeChart(UUID.randomUUID(), owner.orgId(), valid.name(), valid.unitSystem(),
                valid.productTypeCode(), valid.categoryId(), valid.columns(), valid.rows(), valid.fitNote(),
                valid.howToMeasure(), null, now, now, 0));
    }

    /** Saves the new content of an owned chart unless {@code save} is false (a dry run only runs the checks). */
    private SizeChart updateChart(ListingOwner owner, SizeChart current, ChartContent valid, boolean save) {
        requireNameFree(owner.orgId(), valid.name(), current.id());
        SizeChart next = current.replaced(valid, Instant.now(clock));
        requireSizesStillCovered(next);
        return save ? charts.update(next) : next;
    }

    private SizeChart insert(SizeChart chart) {
        try {
            return charts.insert(chart, actorSubject());
        } catch (DuplicateKeyException ex) {
            throw nameTaken(chart.name());
        }
    }

    private String actorSubject() {
        return permissions.require(CatalogPermissions.WRITE).subject();
    }

    /** The product type and category the chart was made for, checked and made consistent. */
    private ChartContent placed(ChartContent c) {
        String type = c.productTypeCode();
        UUID categoryId = c.categoryId();
        if (categoryId != null) {
            CategoryNode node = categories.findById(categoryId).filter(CategoryNode::active)
                    .orElseThrow(() -> new CatalogRuleViolation("Unknown category", Map.of("categoryId", "Unknown category")));
            if (node.level() != CategoryLevel.PRODUCT_TYPE || node.productTypeCode() == null) {
                throw new CatalogRuleViolation("Pick a product type (third level)",
                        Map.of("categoryId", "Pick a product type (third level)"));
            }
            if (type != null && !type.equals(node.productTypeCode())) {
                throw new CatalogRuleViolation("The category and the product type do not match",
                        Map.of("productTypeCode", "Not the product type of that category"));
            }
            type = node.productTypeCode();
        } else if (type != null && categories.findProductType(type).isEmpty()) {
            throw new CatalogRuleViolation("Unknown product type", Map.of("productTypeCode", "Unknown product type"));
        }
        return new ChartContent(c.name(), c.unitSystem(), type, categoryId, c.columns(), c.rows(), c.fitNote(),
                c.howToMeasure());
    }

    private void requireNameFree(String org, String name, UUID ownId) {
        charts.findActiveByName(org, name).filter(other -> !other.id().equals(ownId)).ifPresent(other -> {
            throw nameTaken(name);
        });
    }

    private static CatalogRuleViolation nameTaken(String name) {
        return new CatalogRuleViolation("You already have a size chart called " + name,
                Map.of("name", "You already have a size chart called " + name));
    }

    private String freeCopyName(String org, String name) {
        String base = name.length() > 70 ? name.substring(0, 70).strip() : name;
        String candidate = base + " (copy)";
        for (int i = 2; charts.findActiveByName(org, candidate).isPresent(); i++) {
            candidate = base + " (copy " + i + ")";
        }
        return candidate;
    }

    /** Every size that a product using the chart still sells must keep a row. */
    private void requireSizesStillCovered(SizeChart next) {
        Map<String, String> missing = new LinkedHashMap<>();
        for (Product p : products.findBySizeChart(next.id(), PRODUCT_CHECK_LIMIT)) {
            Optional<SizingTrait> sizing = categories.findProductType(p.productTypeCode()).flatMap(ProductType::sizing);
            if (sizing.isEmpty()) {
                continue;
            }
            for (Variant v : p.variants()) {
                String size = v.options().get(sizing.get().sizeDimension());
                if (size != null && next.row(size).isEmpty()) {
                    missing.putIfAbsent(CustomValues.key(size), size + " (" + p.title() + ")");
                }
            }
        }
        if (!missing.isEmpty()) {
            String message = "Sizes still sold on your products need a row: " + String.join(", ", missing.values());
            throw new CatalogRuleViolation(message, Map.of("rows", message));
        }
    }

    private ListingOwner writingOwner() {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        ListingOwner owner = owners.ownerFor(actor);
        access.requireForOrg(CatalogPermissions.WRITE, CatalogRelations.ORG_MANAGE_PRODUCTS, owner.orgId());
        return owner;
    }

    /** The owner's chart; one of another organisation does not exist for the caller. */
    private SizeChart owned(ListingOwner owner, UUID id) {
        return charts.findById(id).filter(c -> c.ownerOrg().equals(owner.orgId())).orElseThrow(() -> notFound(id));
    }

    private SizeChart visible(Actor actor, UUID id) {
        SizeChart chart = charts.findById(id).orElseThrow(() -> notFound(id));
        if (!isStaff(actor) && !actor.orgId().map(chart.ownerOrg()::equals).orElse(false)) {
            throw notFound(id);
        }
        return chart;
    }

    private ChartView view(SizeChart chart) {
        return new ChartView(chart, products.countBySizeCharts(List.of(chart.id())).getOrDefault(chart.id(), 0L));
    }

    private static boolean isStaff(Actor actor) {
        return actor.type() == PrincipalType.STAFF || actor.type() == PrincipalType.SERVICE;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("catalog.size-chart-not-found", "Size chart " + id + " not found");
    }
}
