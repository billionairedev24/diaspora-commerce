package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.imports.ExistingListing;
import com.afrotransact.catalog.domain.imports.ImageColumns;
import com.afrotransact.catalog.domain.imports.ImageLibrary;
import com.afrotransact.catalog.domain.imports.ImportCategory;
import com.afrotransact.catalog.domain.imports.ImportColumns;
import com.afrotransact.catalog.domain.imports.ImportContext;
import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.ImportStatus;
import com.afrotransact.catalog.domain.imports.ImportTemplates;
import com.afrotransact.catalog.domain.imports.ImportValidator;
import com.afrotransact.catalog.domain.imports.ParsedSheet;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.ReportRow;
import com.afrotransact.catalog.domain.imports.RowImage;
import com.afrotransact.catalog.domain.imports.RowImages;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.Severity;
import com.afrotransact.catalog.domain.imports.SheetRow;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.SpreadsheetRejected;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.domain.imports.TemplateSpec;
import com.afrotransact.catalog.domain.imports.ValidationResult;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.ImageLibraryLookup;
import com.afrotransact.catalog.port.ProductImportRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SpreadsheetReader;
import com.afrotransact.catalog.port.SpreadsheetWriter;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.security.PermissionChecker;
import com.afrotransact.platform.security.relationship.ResourceAccessChecker;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class ProductImportServiceImpl implements ProductImportService {

    private static final Logger LOG = LoggerFactory.getLogger(ProductImportServiceImpl.class);
    private static final int STALLED_BATCH = 10;

    private final ProductImportRepository imports;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final SpreadsheetReader reader;
    private final SpreadsheetWriter writer;
    private final ImportJob job;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final OwnerResolver owners;
    private final ImportSettings settings;
    private final ImageLibraryLookup library;
    private final Executor executor;
    private final Clock clock;
    private final TransactionTemplate tx;

    ProductImportServiceImpl(ProductImportRepository imports, ProductRepository products,
                             CategoryRepository categories, SpreadsheetReader reader, SpreadsheetWriter writer,
                             ImportJob job, PermissionChecker permissions, ResourceAccessChecker access,
                             OwnerResolver owners, ImportSettings settings, ImageLibraryLookup library,
                             @Qualifier("importExecutor") Executor executor, Clock clock,
                             PlatformTransactionManager transactions) {
        this.imports = imports;
        this.products = products;
        this.categories = categories;
        this.reader = reader;
        this.writer = writer;
        this.job = job;
        this.permissions = permissions;
        this.access = access;
        this.owners = owners;
        this.settings = settings;
        this.library = library;
        this.executor = executor;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public FileContent template(String productType, UUID categoryId, SpreadsheetFormat format) {
        permissions.require(CatalogPermissions.WRITE);
        ProductType type = typeOf(productType, categoryId);
        TemplateSpec spec = type == null ? ImportTemplates.generic(leaves(null))
                : ImportTemplates.forType(type, leaves(type.code()));
        return new FileContent(spec.fileStem() + "." + format.extension(), format.contentType(),
                writer.template(spec, format));
    }

    @Override
    public ProductImport upload(String fileName, byte[] content, ImportMode mode, String productType,
                                String uploadedByName) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        ListingOwner owner = owners.ownerFor(actor);
        access.requireForOrg(CatalogPermissions.WRITE, CatalogRelations.ORG_MANAGE_PRODUCTS, owner.orgId());
        SpreadsheetFormat format = SpreadsheetFormat.of(fileName);
        if (format == null) {
            throw new SpreadsheetRejected("Upload an Excel (.xlsx) or CSV (.csv) file");
        }
        ProductType fixed = productType == null || productType.isBlank() ? null : typeOf(productType, null);
        ParsedSheet sheet = ImageColumns.fold(reader.read(content, format));
        ImportContext ctx = context(sheet, mode == null ? ImportMode.CREATE : mode, fixed, owner.orgId());
        ValidationResult result = ImportValidator.validate(sheet, ctx);
        Instant now = Instant.now(clock);
        String name = fileName.replaceAll("[\\\\/]", "_");
        ProductImport upload = new ProductImport(UUID.randomUUID(), imports.nextReference(), owner, actor.subject(),
                blankToNull(uploadedByName), name.length() > 200 ? name.substring(name.length() - 200) : name, format,
                ctx.mode(), fixed == null ? null : fixed.code(), result.header(), result.fileWarnings(),
                ImportStatus.VALIDATED, false, result.rows().size(), (int) result.count(RowStatus.VALID),
                (int) result.count(RowStatus.WARNING), (int) result.count(RowStatus.ERROR), result.productCount(), 0, 0,
                0, 0, result.rows().size() >= settings.notifyMinRows(), now, null, null, null, 0);
        tx.executeWithoutResult(s -> imports.insert(upload, result.rows()));
        LOG.info("Bulk upload {} of {}: {} rows ({} valid, {} warnings, {} errors), {} mode", upload.reference(),
                owner.orgId(), upload.totalRows(), upload.validRows(), upload.warningRows(), upload.errorRows(),
                upload.mode());
        return imports.findById(upload.id()).orElseThrow();
    }

    /** What validation needs from the catalog, for the SKUs and barcodes in the sheet only. */
    private ImportContext context(ParsedSheet sheet, ImportMode mode, ProductType fixed, String orgId) {
        Map<String, ProductType> types = new HashMap<>();
        categories.findAllProductTypes().forEach(t -> types.put(t.code(), t));
        List<ImportCategory> cats = leaves(fixed == null ? null : fixed.code());
        ImportContext probe = new ImportContext(mode, fixed, types, cats, Set.of(), Set.of(), Map.of());
        Map<String, Integer> index = ImportColumns.resolve(sheet.header(), probe.columns());
        Set<String> skus = new LinkedHashSet<>();
        Set<String> parents = new LinkedHashSet<>();
        Set<String> fileNames = new LinkedHashSet<>();
        Set<String> gtins = new LinkedHashSet<>();
        Integer skuCol = index.get(ImportColumns.SKU);
        Integer parentCol = index.get(ImportColumns.PARENT_SKU);
        Integer imageCol = index.get(ImportColumns.IMAGES);
        Integer gtinCol = index.get(ImportColumns.GTIN);
        for (SheetRow r : sheet.rows()) {
            if (skuCol != null && !r.cell(skuCol).isBlank()) {
                skus.add(r.cell(skuCol).strip().toUpperCase(Locale.ROOT));
            }
            if (parentCol != null && !r.cell(parentCol).isBlank()) {
                parents.add(r.cell(parentCol).strip().toUpperCase(Locale.ROOT));
            }
            if (imageCol != null) {
                fileNames.addAll(RowImages.fileNames(r.cell(imageCol)));
            }
            if (gtinCol != null && !r.cell(gtinCol).isBlank()) {
                gtins.add(Gtin.digits(r.cell(gtinCol)));
            }
        }
        Map<String, ProductRepository.SkuOwner> owned = products.productsBySku(orgId, skus);
        Map<String, ExistingListing> existing = new HashMap<>();
        owned.forEach((sku, o) -> existing.put(sku, new ExistingListing(o.productId(), o.productTypeCode())));
        Set<String> existingGtins = mode == ImportMode.CREATE ? products.existingGtins(gtins) : Set.of();
        Set<String> librarySkus = new LinkedHashSet<>(skus);
        librarySkus.addAll(parents);
        return new ImportContext(mode, fixed, types, cats, owned.keySet(), existingGtins, existing,
                libraryFor(orgId, librarySkus, fileNames));
    }

    /** The library images for these SKUs and file names; empty (rows then need links) when media is unavailable. */
    private ImageLibrary libraryFor(String orgId, Set<String> skus, Set<String> fileNames) {
        try {
            return library.lookup(orgId, skus, fileNames);
        } catch (ImageServiceUnavailable ex) {
            LOG.warn("Image library unavailable for {}: {}", orgId, ex.getMessage());
            return ImageLibrary.EMPTY;
        }
    }

    @Override
    public Map<Integer, List<RowImage>> rowImages(UUID id, List<StoredRow> rows) {
        ProductImport u = owned(id, owner());
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, ProductType> types = new HashMap<>();
        categories.findAllProductTypes().forEach(t -> types.put(t.code(), t));
        ProductType fixed = u.productTypeCode() == null ? null : types.get(u.productTypeCode());
        ImportContext probe = new ImportContext(u.mode(), fixed, types, leaves(fixed == null ? null : fixed.code()),
                Set.of(), Set.of(), Map.of());
        Map<String, Integer> index = ImportColumns.resolve(u.header(), probe.columns());
        Integer imageCol = index.get(ImportColumns.IMAGES);
        Integer parentCol = index.get(ImportColumns.PARENT_SKU);
        Set<String> skus = new LinkedHashSet<>();
        Set<String> fileNames = new LinkedHashSet<>();
        for (StoredRow r : rows) {
            if (r.sku() != null) {
                skus.add(r.sku().toUpperCase(Locale.ROOT));
            }
            String parent = cell(r, parentCol);
            if (!parent.isBlank()) {
                skus.add(parent.strip().toUpperCase(Locale.ROOT));
            }
            fileNames.addAll(RowImages.fileNames(cell(r, imageCol)));
        }
        ImageLibrary lib = libraryFor(u.owner().orgId(), skus, fileNames);
        Map<Integer, List<RowImage>> out = new HashMap<>();
        for (StoredRow r : rows) {
            out.put(r.rowNumber(), RowImages.of(lib, cell(r, imageCol), r.sku(), cell(r, parentCol)));
        }
        return out;
    }

    private static String cell(StoredRow r, Integer col) {
        return col == null || col >= r.cells().size() || r.cells().get(col) == null ? "" : r.cells().get(col);
    }

    @Override
    public PageView<ProductImport> list(int page, int size) {
        ListingOwner owner = owner();
        ProductImportRepository.Page<ProductImport> p = imports.list(owner.orgId(), Math.max(page, 0),
                Math.clamp(size, 1, 100));
        return new PageView<>(p.items(), p.total(), p.page(), p.size());
    }

    @Override
    public ProductImport get(UUID id) {
        return owned(id, owner());
    }

    @Override
    public PageView<StoredRow> rows(UUID id, RowStatus status, RowOutcome outcome, int page, int size) {
        ProductImport u = owned(id, owner());
        ProductImportRepository.Page<StoredRow> p = imports.rows(u.id(), status, outcome, Math.max(page, 0),
                Math.clamp(size, 1, 500));
        return new PageView<>(p.items(), p.total(), p.page(), p.size());
    }

    @Override
    public FileContent report(UUID id, SpreadsheetFormat format) {
        ProductImport u = owned(id, owner());
        List<ReportRow> rows = imports.allRows(u.id()).stream()
                .map(r -> new ReportRow(r.rowNumber(), r.cells(), r.status(), result(r), summary(r)))
                .toList();
        String stem = u.fileName().contains(".") ? u.fileName().substring(0, u.fileName().lastIndexOf('.'))
                : u.fileName();
        return new FileContent(stem + "-report." + format.extension(), format.contentType(),
                writer.report(u.header(), rows, format));
    }

    private static String result(StoredRow r) {
        return switch (r.outcome()) {
            case PENDING -> "";
            case CREATED -> "Created";
            case UPDATED -> "Updated";
            case FAILED -> "Failed" + (r.outcomeNote() == null ? "" : ": " + r.outcomeNote());
            case SKIPPED -> "Not imported";
        };
    }

    private static String summary(StoredRow r) {
        return String.join("; ", r.messages().stream().map(m -> (m.severity() == Severity.WARNING
                ? "Warning: " : "") + m.message()).toList());
    }

    @Override
    public ProductImport start(UUID id, boolean validOnly) {
        ListingOwner owner = owner();
        ProductImport started = tx.execute(s -> {
            ProductImport u = imports.lock(id).filter(x -> x.owner().orgId().equals(owner.orgId()))
                    .orElseThrow(() -> notFound(id));
            if (u.status().started()) {
                return u;
            }
            if (u.errorRows() > 0 && !validOnly) {
                throw new ConflictException("catalog.import-has-errors", u.errorRows() + " row(s) have errors: fix "
                        + "them and upload the file again, or import the valid rows only");
            }
            if (u.importableRows() == 0) {
                throw new ConflictException("catalog.import-nothing-valid", "Every row has errors; nothing can be "
                        + "imported");
            }
            if (validOnly) {
                imports.skipErrorRows(u.id());
            }
            return imports.update(u.start(validOnly, Instant.now(clock)));
        });
        if (started != null && started.status().active()) {
            executor.execute(() -> job.drive(started.id()));
        }
        return started;
    }

    @Override
    public int resumeStalled() {
        List<UUID> stalled = imports.stalled(Instant.now(clock).minus(settings.stallAfter()), STALLED_BATCH);
        for (UUID id : stalled) {
            LOG.info("Resuming stalled bulk upload {}", id);
            job.drive(id);
        }
        return stalled.size();
    }

    private ProductType typeOf(String productType, UUID categoryId) {
        if (categoryId != null) {
            CategoryNode node = categories.findById(categoryId).filter(CategoryNode::active)
                    .orElseThrow(() -> new NotFoundException("catalog.category-not-found", "Category not found"));
            if (node.productTypeCode() == null) {
                throw new NotFoundException("catalog.product-type-not-found", "Choose a product type (third level)");
            }
            productType = node.productTypeCode();
        }
        if (productType == null || productType.isBlank()) {
            return null;
        }
        String code = productType.strip();
        return categories.findProductType(code)
                .orElseThrow(() -> new NotFoundException("catalog.product-type-not-found", "Unknown product type " + code));
    }

    /** Product-type categories (the Category dropdown), optionally of one product type. */
    private List<ImportCategory> leaves(String typeCode) {
        return categories.findAllActive().stream()
                .filter(n -> n.level() == CategoryLevel.PRODUCT_TYPE && n.productTypeCode() != null)
                .filter(n -> typeCode == null || typeCode.equals(n.productTypeCode()))
                .map(n -> new ImportCategory(n.id(), n.path(), n.productTypeCode()))
                .toList();
    }

    private ListingOwner owner() {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        ListingOwner owner = owners.ownerFor(actor);
        access.requireForOrg(CatalogPermissions.WRITE, CatalogRelations.ORG_VIEW_PRODUCTS, owner.orgId());
        return owner;
    }

    private ProductImport owned(UUID id, ListingOwner owner) {
        return imports.findById(id).filter(u -> u.owner().orgId().equals(owner.orgId()))
                .orElseThrow(() -> notFound(id));
    }

    private static NotFoundException notFound(UUID id) {
        return new NotFoundException("catalog.import-not-found", "Upload " + id + " not found");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
