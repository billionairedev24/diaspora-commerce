package com.afrotransact.catalog.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.DepartmentScope;
import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.Gtin;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ListingOwner;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.imports.CreateMatchSplit;
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
import com.afrotransact.catalog.domain.imports.OfferImportColumns;
import com.afrotransact.catalog.domain.imports.OfferImportContext;
import com.afrotransact.catalog.domain.imports.OfferImportResult;
import com.afrotransact.catalog.domain.imports.OfferImportValidator;
import com.afrotransact.catalog.domain.imports.OfferTarget;
import com.afrotransact.catalog.domain.imports.PriceQtyResult;
import com.afrotransact.catalog.domain.imports.PriceQtyValidator;
import com.afrotransact.catalog.domain.imports.ParsedSheet;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.ReportRow;
import com.afrotransact.catalog.domain.imports.RowImage;
import com.afrotransact.catalog.domain.imports.RowHashes;
import com.afrotransact.catalog.domain.imports.RowImages;
import com.afrotransact.catalog.domain.imports.RowMessage;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.Severity;
import com.afrotransact.catalog.domain.imports.SheetRow;
import com.afrotransact.catalog.domain.imports.SpreadsheetFormat;
import com.afrotransact.catalog.domain.imports.SpreadsheetRejected;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.domain.imports.TargetKey;
import com.afrotransact.catalog.domain.imports.TemplateSpec;
import com.afrotransact.catalog.domain.imports.ValidatedRow;
import com.afrotransact.catalog.domain.imports.ValidationResult;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.ImageLibraryLookup;
import com.afrotransact.catalog.port.ImportMetrics;
import com.afrotransact.catalog.port.ProductImportRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SizeChartRepository;
import com.afrotransact.catalog.port.SpreadsheetReader;
import com.afrotransact.catalog.port.SpreadsheetWriter;
import com.afrotransact.platform.events.DomainEventPublisher;
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
    private final SizeChartRepository sizeCharts;
    private final CategoryRepository categories;
    private final ListingRulesService rules;
    private final SpreadsheetReader reader;
    private final SpreadsheetWriter writer;
    private final ImportJob job;
    private final PermissionChecker permissions;
    private final ResourceAccessChecker access;
    private final OwnerResolver owners;
    private final ImportSettings settings;
    private final ImageLibraryLookup library;
    private final Executor executor;
    private final SellerScopeGuard scopeGuard;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final ImportMatcher matcher;
    private final ImportMetrics metrics;
    private final DomainEventPublisher events;

    ProductImportServiceImpl(ProductImportRepository imports, ProductRepository products,
                             SizeChartRepository sizeCharts, CategoryRepository categories, SpreadsheetReader reader, SpreadsheetWriter writer,
                             ImportJob job, PermissionChecker permissions, ResourceAccessChecker access,
                             OwnerResolver owners, ImportSettings settings, ImageLibraryLookup library,
                             @Qualifier("importExecutor") Executor executor, SellerScopeGuard scopeGuard, Clock clock,
                             PlatformTransactionManager transactions, ListingRulesService rules, ImportMatcher matcher,
                             ImportMetrics metrics, DomainEventPublisher events) {
        this.matcher = matcher;
        this.metrics = metrics;
        this.events = events;
        this.rules = rules;
        this.imports = imports;
        this.products = products;
        this.sizeCharts = sizeCharts;
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
        this.scopeGuard = scopeGuard;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public FileContent template(ImportMode mode, String productType, UUID categoryId, SpreadsheetFormat format) {
        if (mode == null || !mode.offerRows()) {
            return template(productType, categoryId, format);
        }
        permissions.require(CatalogPermissions.WRITE);
        TemplateSpec spec = mode == ImportMode.OFFER ? OfferImportColumns.offerTemplate()
                : OfferImportColumns.priceQtyTemplate();
        return new FileContent(spec.fileStem() + "." + format.extension(), format.contentType(),
                writer.template(spec, format));
    }

    @Override
    public FileContent template(String productType, UUID categoryId, SpreadsheetFormat format) {
        Actor actor = permissions.require(CatalogPermissions.WRITE);
        DepartmentScope scope = scopeGuard.scopeOf(actor);
        ProductType type = typeOf(productType, categoryId);
        if (type != null) {
            scope.requireProductType(type.code());
        }
        List<ImportCategory> cats = allowed(leaves(type == null ? null : type.code()), scope);
        if (categoryId != null) {
            cats = cats.stream().filter(c -> c.id().equals(categoryId)).toList();
        }
        TemplateSpec spec = type == null ? ImportTemplates.generic(cats) : ImportTemplates.forType(type, cats);
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
        ImportMode wanted = mode == null ? ImportMode.CREATE : mode;
        Instant now = Instant.now(clock);
        requireRoom(owner.orgId(), wanted, now);
        String sha256 = sha256(content);
        ParsedSheet sheet = ImageColumns.fold(reader.read(content, format, settings.rowCap(wanted),
                ImportSettings.byteCap(wanted)));
        ValidationResult result;
        if (wanted == ImportMode.OFFER) {
            Map<String, OfferTarget> targets = matcher.resolve(owner.orgId(), OfferImportValidator.keysOf(sheet));
            OfferImportResult offerResult = OfferImportValidator.validate(sheet,
                    new OfferImportContext(targets, settings.rowCap(wanted)));
            result = offerResult.validation();
        } else if (wanted == ImportMode.PRICE_QTY) {
            PriceQtyResult feed = PriceQtyValidator.validate(sheet, settings.rowCap(wanted));
            result = feed.validation();
        } else {
            result = validateProducts(sheet, wanted, fixed, owner);
        }
        UUID sameFile = imports.findLatestByHash(owner.orgId(), wanted, sha256).map(ProductImport::id).orElse(null);
        String name = fileName.replaceAll("[\\\\/]", "_");
        ProductImport upload = new ProductImport(UUID.randomUUID(), imports.nextReference(), owner, actor.subject(),
                blankToNull(uploadedByName), name.length() > 200 ? name.substring(name.length() - 200) : name, format,
                wanted, fixed == null ? null : fixed.code(), result.header(), result.fileWarnings(),
                ImportStatus.VALIDATED, false, result.rows().size(), (int) result.count(RowStatus.VALID),
                (int) result.count(RowStatus.WARNING), (int) result.count(RowStatus.ERROR), result.productCount(), 0, 0,
                0, 0, result.rows().size() >= settings.notifyMinRows(), now, null, null, null, sha256, sameFile, 0, 0, 0);
        tx.executeWithoutResult(s -> imports.insert(upload, result.rows()));
        metrics.uploaded(wanted.name());
        LOG.info("Bulk upload {} of {}: {} rows ({} valid, {} warnings, {} errors), {} mode", upload.reference(),
                owner.orgId(), upload.totalRows(), upload.validRows(), upload.warningRows(), upload.errorRows(),
                upload.mode());
        return imports.findById(upload.id()).orElseThrow();
    }

    /**
     * Create and update uploads. In a CREATE file, a row whose barcode matches a page somebody else owns is an offer row, not a
     * new page (a warning tells the seller, the report shows the ATIN); the children of a parent group that is only partly on
     * the page are filed as NEW_VARIANT requests (S393). Everything else is validated as before.
     */
    private ValidationResult validateProducts(ParsedSheet sheet, ImportMode mode, ProductType fixed, ListingOwner owner) {
        if (mode != ImportMode.CREATE) {
            return ImportValidator.validate(sheet, context(sheet, mode, fixed, owner));
        }
        // The same file again (S393): rows whose SKU is listed and whose content is unchanged are skipped, not duplicates.
        Map<Integer, String> unchanged = unchangedRows(sheet, owner.orgId());
        if (unchanged.isEmpty()) {
            return validateNew(sheet, fixed, owner);
        }
        ParsedSheet remaining = new ParsedSheet(sheet.header(),
                sheet.rows().stream().filter(r -> !unchanged.containsKey(r.rowNumber())).toList());
        ValidationResult rest = remaining.rows().isEmpty() ? null : validateNew(remaining, fixed, owner);
        List<ValidatedRow> rows = new ArrayList<>(rest == null ? List.of() : rest.rows());
        for (SheetRow r : sheet.rows()) {
            if (unchanged.containsKey(r.rowNumber())) {
                rows.add(new ValidatedRow(r.rowNumber(), MatchedRowsJob.SKIP_GROUP + r.rowNumber(),
                        unchanged.get(r.rowNumber()), RowStatus.VALID, List.of(), r.cells()));
            }
        }
        rows.sort((a, b) -> Integer.compare(a.rowNumber(), b.rowNumber()));
        return new ValidationResult(sheet.header(), rest == null ? List.of() : rest.fileWarnings(), rows,
                rest == null ? List.of() : rest.products(), (rest == null ? 0 : rest.productCount()) + unchanged.size());
    }

    /**
     * Rows of the file that are the same as when they were imported: the SKU is the organisation's and its stored content hash
     * equals the row's. A parent group is skipped only when every one of its rows is.
     */
    private Map<Integer, String> unchangedRows(ParsedSheet sheet, String orgId) {
        Integer skuCol = ImportColumns.resolve(sheet.header(), OfferImportColumns.readable(ImportMode.OFFER))
                .get(OfferImportColumns.SELLER_SKU);
        if (skuCol == null) {
            return Map.of();
        }
        Set<String> skus = new LinkedHashSet<>();
        for (SheetRow r : sheet.rows()) {
            String sku = r.cell(skuCol).strip().toUpperCase(Locale.ROOT);
            if (!sku.isEmpty()) {
                skus.add(sku);
            }
        }
        Map<String, String> stored = imports.rowHashes(orgId, skus);
        if (stored.isEmpty()) {
            return Map.of();
        }
        int parentCol = CreateMatchSplit.parentColumn(sheet.header());
        Map<Integer, String> same = new HashMap<>();
        Map<String, Boolean> groupAllSame = new HashMap<>();
        for (SheetRow r : sheet.rows()) {
            String sku = r.cell(skuCol).strip().toUpperCase(Locale.ROOT);
            boolean unchangedRow = !sku.isEmpty() && RowHashes.of(r.cells()).equals(stored.get(sku));
            if (unchangedRow) {
                same.put(r.rowNumber(), sku.length() > 60 ? sku.substring(0, 60) : sku);
            }
            String parent = parentCol < 0 ? "" : r.cell(parentCol).strip().toUpperCase(Locale.ROOT);
            if (!parent.isEmpty()) {
                groupAllSame.merge(parent, unchangedRow, (a, b) -> a && b);
            }
        }
        if (parentCol >= 0) {
            same.keySet().removeIf(n -> {
                SheetRow row = sheet.rows().stream().filter(x -> x.rowNumber() == n).findFirst().orElseThrow();
                String parent = row.cell(parentCol).strip().toUpperCase(Locale.ROOT);
                return !parent.isEmpty() && !groupAllSame.getOrDefault(parent, true);
            });
        }
        return same;
    }

    private ValidationResult validateNew(ParsedSheet sheet, ProductType fixed, ListingOwner owner) {
        ImportMode mode = ImportMode.CREATE;
        ImportContext ctx = context(sheet, mode, fixed, owner);
        List<TargetKey> keys = CreateMatchSplit.keysOf(sheet);
        if (keys.isEmpty()) {
            return ImportValidator.validate(sheet, ctx);
        }
        Map<String, OfferTarget> targets = matcher.resolve(owner.orgId(), keys);
        CreateMatchSplit.Result split = CreateMatchSplit.split(sheet, targets, owner.orgId());
        if (!split.any()) {
            return ImportValidator.validate(sheet, ctx);
        }
        ValidationResult rest = split.rest().isEmpty() ? null
                : ImportValidator.validate(new ParsedSheet(sheet.header(), split.rest()), ctx);
        OfferImportResult offers = OfferImportValidator.validate(new ParsedSheet(sheet.header(), split.offers()),
                new OfferImportContext(targets, Integer.MAX_VALUE));
        List<ValidatedRow> rows = new ArrayList<>(rest == null ? List.of() : rest.rows());
        for (ValidatedRow v : offers.validation().rows()) {
            OfferTarget target = split.matched().get(v.rowNumber());
            List<RowMessage> messages = new ArrayList<>();
            messages.add(RowMessage.warning(ImportColumns.GTIN, -1, "This barcode is already on AfroTransact"
                    + (target == null ? "" : " (" + target.atin() + ")") + ". We added your offer to that page and used its "
                    + "photos and details."));
            messages.addAll(v.messages());
            boolean error = messages.stream().anyMatch(m -> m.severity() == Severity.ERROR);
            rows.add(new ValidatedRow(v.rowNumber(), MatchedRowsJob.OFFER_GROUP + v.rowNumber(), v.sku(),
                    error ? RowStatus.ERROR : RowStatus.WARNING, messages, v.cells()));
        }
        Integer skuCol = ImportColumns.resolve(sheet.header(), OfferImportColumns.readable(ImportMode.OFFER))
                .get(OfferImportColumns.SELLER_SKU);
        for (SheetRow r : split.variants()) {
            String sku = skuCol == null ? "" : r.cell(skuCol).strip().toUpperCase(Locale.ROOT);
            rows.add(new ValidatedRow(r.rowNumber(), MatchedRowsJob.VARIANT_GROUP + r.rowNumber(),
                    sku.length() > 60 ? sku.substring(0, 60) : sku, RowStatus.WARNING, List.of(RowMessage.warning(null, -1,
                    "This size or colour is not on the page yet. We send it for review, and the other sizes or colours "
                            + "become offers.")), r.cells()));
        }
        rows.sort((a, b) -> Integer.compare(a.rowNumber(), b.rowNumber()));
        List<String> fileWarnings = new ArrayList<>(rest == null ? List.of() : rest.fileWarnings());
        offers.validation().fileWarnings().stream().filter(w -> !fileWarnings.contains(w)).forEach(fileWarnings::add);
        return new ValidationResult(sheet.header(), fileWarnings, rows, rest == null ? List.of() : rest.products(),
                (rest == null ? 0 : rest.productCount()) + split.offers().size() + split.variants().size());
    }

    /** One upload per mode at a time and a daily limit per organisation (S396); refused before anything is stored. */
    private void requireRoom(String orgId, ImportMode mode, Instant now) {
        if (imports.hasActive(orgId, mode)) {
            metrics.refused(mode.name(), "ACTIVE_UPLOAD");
            throw new ConflictException("catalog.import-already-running", "An earlier " + describe(mode) + " upload is "
                    + "still running. Wait for it to finish, then upload this file.");
        }
        if (imports.countCreatedSince(orgId, now.minus(Duration.ofHours(24))) >= settings.maxUploadsPerDay()) {
            metrics.refused(mode.name(), "DAILY_LIMIT");
            throw new ConflictException("catalog.import-daily-limit", "You have uploaded " + settings.maxUploadsPerDay()
                    + " files in the last 24 hours, which is the limit. Try again tomorrow, or put more rows in one file.");
        }
    }

    private static String describe(ImportMode mode) {
        return switch (mode) {
            case CREATE -> "product";
            case UPDATE -> "listing update";
            case OFFER -> "offer";
            case PRICE_QTY -> "price and quantity";
        };
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * Update mode: rows without a SKU name their listing by barcode, else by ATIN. Adds the listings those name, keyed
     * {@code gtin:<digits>} and {@code atin:<ATIN>} and by their own SKU, so the update writes by the real SKU.
     */
    private void addIdentityEntries(Map<String, ExistingListing> existing, String orgId, ParsedSheet sheet,
                                    Map<String, Integer> index) {
        Integer gtinCol = index.get(ImportColumns.GTIN);
        Integer atinCol = index.get(ImportColumns.ATIN);
        for (SheetRow r : sheet.rows()) {
            if (gtinCol != null && !r.cell(gtinCol).isBlank()) {
                String gtin = Gtin.digits(r.cell(gtinCol));
                products.findByVariantGtin(gtin).filter(p -> p.owner().orgId().equals(orgId)).ifPresent(p -> p.variants()
                        .stream().filter(v -> gtin.equals(v.gtin())).findFirst().ifPresent(v -> remember(existing,
                                ExistingListing.GTIN_KEY + gtin, p, v.sellerSku())));
            }
            if (atinCol != null && !r.cell(atinCol).isBlank()) {
                String atin = r.cell(atinCol).strip().toUpperCase(Locale.ROOT);
                try {
                    products.findByVariantAtin(new Atin(atin)).filter(p -> p.owner().orgId().equals(orgId))
                            .flatMap(p -> p.variants().stream().filter(v -> v.atin().value().equals(atin)).findFirst()
                                    .map(v -> Map.entry(p, v.sellerSku())))
                            .ifPresent(e -> remember(existing, ExistingListing.ATIN_KEY + atin, e.getKey(), e.getValue()));
                } catch (IllegalArgumentException ex) {
                    // Not an ATIN: the row's own validation says so.
                }
            }
        }
    }

    private static void remember(Map<String, ExistingListing> existing, String key, Product p, String sku) {
        ExistingListing listing = new ExistingListing(p.id(), p.productTypeCode(), sku);
        existing.put(key, listing);
        existing.putIfAbsent(sku, listing);
    }

    /** What validation needs from the catalog, for the SKUs and barcodes in the sheet only. */
    private ImportContext context(ParsedSheet sheet, ImportMode mode, ProductType fixed, ListingOwner owner) {
        String orgId = owner.orgId();
        DepartmentScope scope = scopeGuard.scopeOf(owner);
        Map<String, ProductType> types = new HashMap<>();
        categories.findAllProductTypes().forEach(t -> types.put(t.code(), t));
        List<ImportCategory> every = leaves(fixed == null ? null : fixed.code());
        List<ImportCategory> cats = allowed(every, scope);
        List<ImportCategory> blocked = every.stream().filter(c -> !scope.allowsPath(c.path())).toList();
        ImportContext probe = new ImportContext(mode, fixed, types, cats, Set.of(), Set.of(), Map.of());
        Map<String, Integer> index = ImportColumns.resolve(sheet.header(), probe.columns());
        Set<String> skus = new LinkedHashSet<>();
        Set<String> parents = new LinkedHashSet<>();
        Set<String> fileNames = new LinkedHashSet<>();
        Set<String> gtins = new LinkedHashSet<>();
        Integer skuCol = index.get(ImportColumns.SKU);
        Integer parentCol = index.get(ImportColumns.PARENT_SKU);
        Integer imageCol = index.get(ImportColumns.IMAGES);
        Integer labelCol = index.get(ImportColumns.LABEL_PHOTO);
        Integer gtinCol = index.get(ImportColumns.GTIN);
        Integer chartCol = index.get(ImportColumns.SIZE_CHART);
        Set<String> chartNames = new LinkedHashSet<>();
        for (SheetRow r : sheet.rows()) {
            if (chartCol != null && !r.cell(chartCol).isBlank()) {
                chartNames.add(r.cell(chartCol).strip());
            }
            if (skuCol != null && !r.cell(skuCol).isBlank()) {
                skus.add(r.cell(skuCol).strip().toUpperCase(Locale.ROOT));
            }
            if (parentCol != null && !r.cell(parentCol).isBlank()) {
                parents.add(r.cell(parentCol).strip().toUpperCase(Locale.ROOT));
            }
            if (imageCol != null) {
                fileNames.addAll(RowImages.fileNames(r.cell(imageCol)));
            }
            if (labelCol != null) {
                fileNames.addAll(RowImages.fileNames(r.cell(labelCol)));
            }
            if (gtinCol != null && !r.cell(gtinCol).isBlank()) {
                gtins.add(Gtin.digits(r.cell(gtinCol)));
            }
        }
        Map<String, ProductRepository.SkuOwner> owned = products.productsBySku(orgId, skus);
        Map<String, ExistingListing> existing = new HashMap<>();
        owned.forEach((sku, o) -> existing.put(sku, new ExistingListing(o.productId(), o.productTypeCode(), sku)));
        if (mode == ImportMode.UPDATE) {
            addIdentityEntries(existing, orgId, sheet, index);
        }
        Set<String> existingGtins = mode == ImportMode.CREATE ? products.existingGtins(gtins) : Set.of();
        Set<String> librarySkus = new LinkedHashSet<>(skus);
        librarySkus.addAll(parents);
        return new ImportContext(mode, fixed, types, cats, owned.keySet(), existingGtins, existing,
                libraryFor(orgId, librarySkus, fileNames), orgId, sizeCharts.activeByNames(orgId, chartNames), blocked);
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
                .map(r -> new ReportRow(r.rowNumber(), r.cells(), r.status(), result(r), summary(r),
                        r.atin() == null ? "" : r.atin(), severity(r), firstColumn(r, u.header())))
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
            case OFFER_CREATED -> "Offer created";
            case HELD -> "Held" + (r.outcomeNote() == null ? "" : ": " + r.outcomeNote());
            case FAILED -> "Failed" + (r.outcomeNote() == null ? "" : ": " + r.outcomeNote());
            case SKIPPED -> r.outcomeNote() == null || r.outcomeNote().startsWith("Not imported") ? "Not imported"
                    : "Skipped: " + r.outcomeNote();
        };
    }

    /** Error when any message of the row is an error, else Warning; blank when the row has no message. */
    private static String severity(StoredRow r) {
        if (r.messages().isEmpty()) {
            return "";
        }
        return r.messages().stream().anyMatch(m -> m.severity() == Severity.ERROR) ? "Error" : "Warning";
    }

    /** The seller's own header text of the column the first message is about; blank for the row as a whole. */
    private static String firstColumn(StoredRow r, List<String> header) {
        return r.messages().stream().filter(m -> m.columnIndex() >= 0 && m.columnIndex() < header.size())
                .findFirst().map(m -> header.get(m.columnIndex())).orElse("");
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
            ProductImport begun = imports.update(u.start(validOnly, Instant.now(clock)));
            events.publish(CatalogEvents.IMPORT_STARTED, CatalogEvents.AGGREGATE_IMPORT, begun.id().toString(),
                    ImportStartedPayload.of(begun));
            return begun;
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

    private static List<ImportCategory> allowed(List<ImportCategory> cats, DepartmentScope scope) {
        return cats.stream().filter(c -> scope.allowsPath(c.path())).toList();
    }

    /** Product-type categories (the Category dropdown), optionally of one product type. */
    private List<ImportCategory> leaves(String typeCode) {
        return ImportCategories.leaves(categories.findAllActive(), typeCode, rules.book());
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
