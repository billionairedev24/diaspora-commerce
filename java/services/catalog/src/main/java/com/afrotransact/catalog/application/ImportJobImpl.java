package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.CatalogRuleViolation;
import com.afrotransact.catalog.domain.CategoryLevel;
import com.afrotransact.catalog.domain.ListingDetails;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductImage;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.Variant;
import com.afrotransact.catalog.domain.VariantDraft;
import com.afrotransact.catalog.domain.imports.ExistingListing;
import com.afrotransact.catalog.domain.imports.ImportCategory;
import com.afrotransact.catalog.domain.imports.ImportContext;
import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.ImportValidator;
import com.afrotransact.catalog.domain.imports.ImportedProduct;
import com.afrotransact.catalog.domain.imports.ImportedVariant;
import com.afrotransact.catalog.domain.imports.ParsedSheet;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.RowStatus;
import com.afrotransact.catalog.domain.imports.SheetRow;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.domain.imports.ValidatedRow;
import com.afrotransact.catalog.domain.imports.ValidationResult;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ImageImporter;
import com.afrotransact.catalog.port.ImageImporter.ImageServiceUnavailable;
import com.afrotransact.catalog.port.ImageImporter.ImportedImage;
import com.afrotransact.catalog.port.ProductImportRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.events.DomainEventPublisher;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives one upload product by product. Each product is written in one transaction that holds the upload's row lock,
 * re-checks that its rows are still pending and records their outcome with the product, so a retry after a crash
 * (or a second pod resuming the same upload) never creates a product twice: the idempotency key is the upload id
 * plus the row SKU. The product write runs in a savepoint, so a refused product only marks its rows FAILED. Images
 * are copied through media before the transaction (a retry may copy an image again; the product is still created
 * once).
 */
@Component
class ImportJobImpl implements ImportJob {

    private static final Logger LOG = LoggerFactory.getLogger(ImportJobImpl.class);

    private final ProductImportRepository imports;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final ProductWriter writer;
    private final ImageImporter images;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate savepoint;

    ImportJobImpl(ProductImportRepository imports, ProductRepository products, CategoryRepository categories,
                  ProductWriter writer, ImageImporter images, DomainEventPublisher events, Clock clock,
                  PlatformTransactionManager transactions) {
        this.imports = imports;
        this.products = products;
        this.categories = categories;
        this.writer = writer;
        this.images = images;
        this.events = events;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.savepoint = new TransactionTemplate(transactions);
        this.savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Override
    public void drive(UUID importId) {
        try {
            while (true) {
                Next next = tx.execute(s -> next(importId));
                if (next == null) {
                    return;
                }
                Prepared prepared = prepare(next.upload(), next.rows());
                tx.executeWithoutResult(s -> write(importId, next.groupKey(), prepared));
            }
        } catch (RuntimeException ex) {
            // The upload stays RUNNING: the resume job picks it up after stall-after.
            LOG.error("Bulk upload {} stopped; it will be resumed", importId, ex);
        }
    }

    private record Next(ProductImport upload, String groupKey, List<StoredRow> rows) {
    }

    /** Locks the upload, records progress and returns the next pending product, or finishes the upload. */
    private Next next(UUID importId) {
        ProductImport u = imports.lock(importId).orElse(null);
        if (u == null || !u.status().active()) {
            return null;
        }
        Instant now = Instant.now(clock);
        List<String> groups = imports.pendingGroups(importId, 1);
        if (groups.isEmpty()) {
            ProductImport done = imports.update(u.completed(now));
            int skipped = (int) imports.rows(importId, null, RowOutcome.SKIPPED, 0, 1).total();
            events.publish(CatalogEvents.IMPORT_COMPLETED, CatalogEvents.AGGREGATE_IMPORT, done.id().toString(),
                    ImportCompletedPayload.of(done, skipped));
            LOG.info("Bulk upload {} done: {} created, {} updated, {} failed, {} skipped", done.reference(),
                    done.createdProducts(), done.updatedProducts(), done.failedRows(), skipped);
            return null;
        }
        ProductImport running = imports.update(u.running(now));
        return new Next(running, groups.getFirst(), imports.group(importId, groups.getFirst()));
    }

    /** What to write for one product: the command, or why its rows fail. */
    private record Prepared(List<Integer> rows, CreateProductCommand create, UpdateProductCommand update,
                            UUID productId, String failure, String note) {
    }

    private Prepared prepare(ProductImport u, List<StoredRow> stored) {
        List<StoredRow> rows = stored.stream()
                .filter(r -> r.outcome() == RowOutcome.PENDING && r.status() != RowStatus.ERROR).toList();
        List<Integer> numbers = rows.stream().map(StoredRow::rowNumber).toList();
        if (rows.isEmpty()) {
            return new Prepared(numbers, null, null, null, null, null);
        }
        try {
            ImportedProduct p = remap(u, rows);
            if (u.mode() == ImportMode.CREATE) {
                List<String> notes = new ArrayList<>();
                List<ProductImage> imgs = fetchImages(u, p.imageUrls(), notes);
                return new Prepared(numbers, toCreate(p, imgs), null, null, null, String.join("; ", notes));
            }
            Product current = products.findById(p.productId())
                    .orElseThrow(() -> new NotFoundException("catalog.product-not-found", "The listing was deleted"));
            List<String> notes = new ArrayList<>();
            List<ProductImage> imgs = p.imageUrls().isEmpty() ? current.images() : fetchImages(u, p.imageUrls(), notes);
            return new Prepared(numbers, null, toUpdate(current, p, imgs), current.id(), null, String.join("; ", notes));
        } catch (ImageServiceUnavailable ex) {
            return new Prepared(numbers, null, null, null, "Images could not be copied (image service unavailable); "
                    + "upload the file again to retry these rows", null);
        } catch (ImportRefused ex) {
            return new Prepared(numbers, null, null, null, ex.getMessage(), null);
        } catch (NotFoundException ex) {
            return new Prepared(numbers, null, null, null, ex.getMessage(), null);
        }
    }

    /** Reads the product from its stored rows with the catalog as it is now (a category may have changed). */
    private ImportedProduct remap(ProductImport u, List<StoredRow> rows) {
        Map<String, ProductType> types = new HashMap<>();
        categories.findAllProductTypes().forEach(t -> types.put(t.code(), t));
        ProductType fixed = u.productTypeCode() == null ? null : types.get(u.productTypeCode());
        if (u.productTypeCode() != null && fixed == null) {
            throw new ImportRefused("The template's product type is no longer available");
        }
        List<ImportCategory> cats = categories.findAllActive().stream()
                .filter(n -> n.level() == CategoryLevel.PRODUCT_TYPE && n.productTypeCode() != null)
                .filter(n -> fixed == null || fixed.code().equals(n.productTypeCode()))
                .map(n -> new ImportCategory(n.id(), n.path(), n.productTypeCode()))
                .toList();
        Map<String, ExistingListing> existing = new LinkedHashMap<>();
        if (u.mode() == ImportMode.UPDATE) {
            UUID productId = UUID.fromString(rows.getFirst().groupKey());
            Product current = products.findById(productId)
                    .orElseThrow(() -> new ImportRefused("The listing was deleted"));
            rows.forEach(r -> existing.put(r.sku(), new ExistingListing(productId, current.productTypeCode())));
        }
        ImportContext ctx = new ImportContext(u.mode(), fixed, types, cats, Set.of(), Set.of(),
                existing);
        ParsedSheet sheet = new ParsedSheet(u.header(),
                rows.stream().map(r -> new SheetRow(r.rowNumber(), r.cells())).toList());
        ValidationResult result = ImportValidator.validate(sheet, ctx);
        if (result.products().isEmpty()) {
            String why = result.rows().stream().filter(r -> r.status() == RowStatus.ERROR)
                    .map(ValidatedRow::summary).findFirst().orElse("The rows are no longer valid");
            throw new ImportRefused(why);
        }
        return result.products().getFirst();
    }

    private List<ProductImage> fetchImages(ProductImport u, List<String> urls, List<String> notes) {
        List<ProductImage> out = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            ImportedImage img = images.importImage(urls.get(i), u.owner().orgId(), u.uploadedBy());
            if (img.ready()) {
                out.add(new ProductImage(img.url(), img.width(), img.height(), img.bytes(), img.contentType()));
                continue;
            }
            String why = "FLAGGED".equals(img.status()) ? "held for review by moderation"
                    : img.reason() == null ? "refused" : img.reason();
            if (i == 0) {
                throw new ImportRefused("Main image " + urls.get(i) + ": " + why);
            }
            notes.add("Image " + (i + 1) + " skipped: " + why);
        }
        return out;
    }

    private static CreateProductCommand toCreate(ImportedProduct p, List<ProductImage> imgs) {
        List<VariantDraft> variants = p.variants().stream().map(v -> new VariantDraft(v.options(), v.sku(), v.gtin(),
                v.gtinExempt(), v.priceMinor() == null ? 0 : v.priceMinor(), v.quantity() == null ? 0 : v.quantity(),
                v.compareAtMinor(), List.of())).toList();
        ListingDetails details = new ListingDetails(p.description(), p.condition(), p.keywords(), p.fulfilment(),
                p.packaging(), p.handlingDays() == null ? 0 : p.handlingDays(), null);
        return new CreateProductCommand(p.category().id(), null, p.title(), p.attributes(), imgs, p.variationTheme(),
                variants, "USD", p.preorderEligible(), null, details, null);
    }

    /** The current listing with the row's non-blank values applied. */
    private static UpdateProductCommand toUpdate(Product current, ImportedProduct p, List<ProductImage> imgs) {
        Map<String, ImportedVariant> bySku = new HashMap<>();
        p.variants().forEach(v -> bySku.put(v.sku(), v));
        List<VariantDraft> variants = new ArrayList<>();
        for (Variant v : current.variants()) {
            ImportedVariant change = bySku.get(v.sellerSku());
            long price = change != null && change.priceMinor() != null ? change.priceMinor() : v.priceMinor();
            int qty = change != null && change.quantity() != null ? change.quantity() : v.quantity();
            Long compareAt = change != null && change.compareAtMinor() != null ? change.compareAtMinor()
                    : v.compareAtMinor();
            if (compareAt != null && compareAt <= price) {
                compareAt = null;
            }
            variants.add(new VariantDraft(v.options(), v.sellerSku(), v.gtin(), v.gtinExempt(), price, qty, compareAt,
                    v.images()));
        }
        Map<String, String> attributes = new LinkedHashMap<>(current.attributes());
        attributes.putAll(p.attributes());
        ListingDetails d = current.details();
        ListingDetails details = new ListingDetails(p.description() != null ? p.description() : d.description(),
                p.condition() != null ? p.condition() : d.condition(),
                p.keywords() != null ? p.keywords() : d.keywords(), d.fulfilment(),
                p.packaging() != null ? p.packaging() : d.packaging(),
                p.handlingDays() != null ? p.handlingDays() : d.handlingDays(), d.shipFrom());
        return new UpdateProductCommand(null, p.title() != null ? p.title() : current.title(), attributes, imgs,
                current.variationTheme(), variants, p.preorderEligible(), null, details, null, null);
    }

    /** Writes one product and its rows' outcome, unless another run already did. */
    private void write(UUID importId, String groupKey, Prepared prepared) {
        ProductImport u = imports.lock(importId).orElse(null);
        if (u == null || !u.status().active()) {
            return;
        }
        List<Integer> pending = imports.group(importId, groupKey).stream()
                .filter(r -> r.outcome() == RowOutcome.PENDING && r.status() != RowStatus.ERROR)
                .map(StoredRow::rowNumber).toList();
        if (pending.isEmpty() || !pending.equals(prepared.rows())) {
            return; // another pod finished (or is finishing) this product
        }
        Instant now = Instant.now(clock);
        if (prepared.failure() != null) {
            imports.setOutcome(importId, pending, RowOutcome.FAILED, truncate(prepared.failure()), null);
            imports.update(u.progressed(pending.size(), 0, 0, pending.size(), now));
            return;
        }
        try {
            Product written = savepoint.execute(s -> prepared.create() != null
                    ? writer.create(u.owner(), prepared.create())
                    : writer.update(products.findById(prepared.productId()).orElseThrow(), prepared.update()));
            boolean created = prepared.create() != null;
            imports.setOutcome(importId, pending, created ? RowOutcome.CREATED : RowOutcome.UPDATED,
                    blankToNull(prepared.note()), Objects.requireNonNull(written).id());
            imports.update(u.progressed(pending.size(), created ? 1 : 0, created ? 0 : 1, 0, now));
        } catch (CatalogRuleViolation ex) {
            String fields = ex.fieldErrors().isEmpty() ? "" : " (" + String.join(", ", ex.fieldErrors().entrySet()
                    .stream().map(e -> e.getKey() + ": " + e.getValue()).toList()) + ")";
            fail(importId, u, pending, ex.getMessage() + fields, now);
        } catch (ConflictException | NotFoundException | IllegalStateException | IllegalArgumentException ex) {
            fail(importId, u, pending, ex.getMessage(), now);
        }
    }

    private void fail(UUID importId, ProductImport u, List<Integer> rows, String why, Instant now) {
        imports.setOutcome(importId, rows, RowOutcome.FAILED, truncate(why), null);
        imports.update(u.progressed(rows.size(), 0, 0, rows.size(), now));
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1000 ? s.substring(0, 999) + "…" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** A product of the upload that cannot be written (main image refused, rows no longer valid). */
    private static final class ImportRefused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ImportRefused(String message) {
            super(message);
        }
    }
}
