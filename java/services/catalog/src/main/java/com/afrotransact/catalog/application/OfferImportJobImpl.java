package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.FulfilmentMode;
import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.OfferImportContext;
import com.afrotransact.catalog.domain.imports.OfferImportResult;
import com.afrotransact.catalog.domain.imports.OfferImportValidator;
import com.afrotransact.catalog.domain.imports.OfferRow;
import com.afrotransact.catalog.domain.imports.OfferTarget;
import com.afrotransact.catalog.domain.imports.ParsedSheet;
import com.afrotransact.catalog.domain.imports.PriceQtyResult;
import com.afrotransact.catalog.domain.imports.PriceQtyRow;
import com.afrotransact.catalog.domain.imports.PriceQtyValidator;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.SheetRow;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.domain.imports.ValidatedRow;
import com.afrotransact.catalog.port.ImportMetrics;
import com.afrotransact.catalog.port.OfferWriter;
import com.afrotransact.catalog.port.OfferWriter.OfferTerms;
import com.afrotransact.catalog.port.OfferWriter.OffersUnavailable;
import com.afrotransact.catalog.port.OfferWriter.PriceStock;
import com.afrotransact.catalog.port.OfferWriter.Result;
import com.afrotransact.catalog.port.ProductImportRepository;
import com.afrotransact.catalog.port.ProductImportRepository.AuditRow;
import com.afrotransact.catalog.port.ProductImportRepository.RowResult;
import com.afrotransact.platform.events.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class OfferImportJobImpl implements OfferImportJob {

    private static final Logger LOG = LoggerFactory.getLogger(OfferImportJobImpl.class);
    /** Offer rows per call to offers-service: each is its own transaction there, with the eligibility checks. */
    static final int OFFER_BATCH = 100;
    /** Feed rows per call (the most offers-service takes). */
    static final int FEED_BATCH = 500;
    private static final String APPROVAL_CODE = "offers.selling-approval-required";
    private static final String NO_CHANGE = "No change.";

    private final ProductImportRepository imports;
    private final ImportMatcher matcher;
    private final OfferWriter offers;
    private final DomainEventPublisher events;
    private final ImportMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate tx;

    OfferImportJobImpl(ProductImportRepository imports, ImportMatcher matcher, OfferWriter offers,
                       DomainEventPublisher events, ImportMetrics metrics, Clock clock,
                       PlatformTransactionManager transactions) {
        this.imports = imports;
        this.matcher = matcher;
        this.offers = offers;
        this.events = events;
        this.metrics = metrics;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public void drive(UUID importId) {
        try {
            while (true) {
                Batch batch = tx.execute(s -> next(importId));
                if (batch == null) {
                    return;
                }
                List<RowResult> results = process(batch);
                tx.executeWithoutResult(s -> save(importId, batch, results));
            }
        } catch (OffersUnavailable ex) {
            // Nothing was marked: the rows stay pending and the resume job continues after stall-after.
            LOG.warn("Bulk upload {} paused: offers-service did not answer ({})", importId, ex.getMessage());
        } catch (RuntimeException ex) {
            LOG.error("Bulk upload {} stopped; it will be resumed", importId, ex);
        }
    }

    private record Batch(ProductImport upload, List<StoredRow> rows) {
    }

    /** Locks the upload, records progress and returns the next pending rows, or completes the upload. */
    private Batch next(UUID importId) {
        ProductImport u = imports.lock(importId).orElse(null);
        if (u == null || !u.status().active()) {
            return null;
        }
        Instant now = Instant.now(clock);
        List<StoredRow> rows = imports.pendingRows(importId, u.mode() == ImportMode.PRICE_QTY ? FEED_BATCH : OFFER_BATCH);
        if (rows.isEmpty()) {
            ProductImport done = imports.update(u.completed(now));
            int skipped = (int) imports.rows(importId, null, RowOutcome.SKIPPED, 0, 1).total();
            events.publish(CatalogEvents.IMPORT_COMPLETED, CatalogEvents.AGGREGATE_IMPORT, done.id().toString(),
                    ImportCompletedPayload.of(done, skipped));
            LOG.info("Bulk {} upload {} done: {} offers created, {} changed, {} held, {} failed, {} skipped",
                    done.mode(), done.reference(), done.offersCreated(), done.updatedProducts(), done.heldRows(),
                    done.failedRows(), skipped);
            return null;
        }
        return new Batch(imports.update(u.running(now)), rows);
    }

    // ------------------------------------------------------------------ processing

    private List<RowResult> process(Batch batch) {
        return batch.upload().mode() == ImportMode.PRICE_QTY ? feed(batch) : offerRows(batch);
    }

    private static ParsedSheet sheetOf(Batch batch) {
        return new ParsedSheet(batch.upload().header(),
                batch.rows().stream().map(r -> new SheetRow(r.rowNumber(), r.cells())).toList());
    }

    /** Offer rows: match again now (a page may have changed), hold what is not cleared, send the rest. */
    private List<RowResult> offerRows(Batch batch) {
        ProductImport u = batch.upload();
        ParsedSheet sheet = sheetOf(batch);
        Map<String, OfferTarget> targets = matcher.resolve(u.owner().orgId(),
                OfferImportValidator.keysOf(sheet));
        OfferImportResult checked = OfferImportValidator.validate(sheet, new OfferImportContext(targets, Integer.MAX_VALUE));
        Map<Integer, ValidatedRow> validated = new HashMap<>();
        checked.validation().rows().forEach(r -> validated.put(r.rowNumber(), r));
        Map<Integer, OfferRow> typed = new HashMap<>();
        checked.importable().forEach(r -> typed.put(r.rowNumber(), r));

        Map<Integer, RowResult> done = new LinkedHashMap<>();
        List<OfferTerms> toSend = new ArrayList<>();
        Map<String, OfferRow> sent = new HashMap<>();
        for (StoredRow stored : batch.rows()) {
            OfferRow row = typed.get(stored.rowNumber());
            if (row == null) {
                ValidatedRow v = validated.get(stored.rowNumber());
                done.put(stored.rowNumber(), new RowResult(stored.rowNumber(), RowOutcome.FAILED, v == null
                        ? "The row could not be read again" : truncate(v.summary()), null, null, null));
            } else if (row.held()) {
                done.put(stored.rowNumber(), new RowResult(stored.rowNumber(), RowOutcome.HELD, row.heldNote(),
                        row.atin(), null, null));
            } else {
                OfferTerms terms = terms(row);
                toSend.add(terms);
                sent.put(terms.rowKey(), row);
            }
        }
        if (!toSend.isEmpty()) {
            List<Result> results = offers.upsert(u.owner().orgId(), u.id(), u.uploadedBy(), toSend);
            for (Result r : results) {
                OfferRow row = sent.get(r.rowKey());
                if (row != null) {
                    done.put(row.rowNumber(), fromOffers(row.rowNumber(), r, row.atin()));
                }
            }
        }
        return batch.rows().stream().map(r -> done.getOrDefault(r.rowNumber(), new RowResult(r.rowNumber(),
                RowOutcome.FAILED, "offers-service gave no answer for this row. Upload the file again.", null, null,
                null))).toList();
    }

    private static OfferTerms terms(OfferRow r) {
        return new OfferTerms(Integer.toString(r.rowNumber()), r.atin(), r.sku(), r.condition().name(), r.priceMinor(),
                r.listPriceMinor(), r.shippingMinor(), r.fulfilment() == FulfilmentMode.AFROTRANSACT ? "AFROTRANSACT"
                        : "SELLER", r.quantity(), r.handlingDays(),
                r.fulfilment() == FulfilmentMode.AFROTRANSACT ? null : new OfferWriter.ShipFrom(blankToNull(r.shipCity()),
                        blankToNull(r.shipRegion()), r.shipCountry(), blankToNull(r.shipZip())),
                r.conditionNote(), r.box() == null ? null : new OfferWriter.Box(r.box().lengthIn(), r.box().widthIn(),
                        r.box().heightIn(), r.box().weightLb()));
    }

    /** The feed: rows with nothing to change are skipped here, the others go to the batch endpoint. */
    private List<RowResult> feed(Batch batch) {
        ProductImport u = batch.upload();
        PriceQtyResult checked = PriceQtyValidator.validate(sheetOf(batch), Integer.MAX_VALUE);
        Map<Integer, PriceQtyRow> typed = new HashMap<>();
        checked.importable().forEach(r -> typed.put(r.rowNumber(), r));
        Map<Integer, ValidatedRow> validated = new HashMap<>();
        checked.validation().rows().forEach(r -> validated.put(r.rowNumber(), r));

        Map<Integer, RowResult> done = new LinkedHashMap<>();
        List<PriceStock> toSend = new ArrayList<>();
        for (StoredRow stored : batch.rows()) {
            PriceQtyRow row = typed.get(stored.rowNumber());
            if (row == null) {
                ValidatedRow v = validated.get(stored.rowNumber());
                done.put(stored.rowNumber(), new RowResult(stored.rowNumber(), RowOutcome.FAILED, v == null
                        ? "The row could not be read again" : truncate(v.summary()), null, null, null));
            } else if (row.priceMinor() == null && row.listPriceMinor() == null && row.quantity() == null) {
                done.put(stored.rowNumber(), new RowResult(stored.rowNumber(), RowOutcome.SKIPPED,
                        "Nothing to change on this row.", row.atin(), null, null));
            } else {
                toSend.add(new PriceStock(Integer.toString(row.rowNumber()), row.sku(), row.atin(), row.priceMinor(),
                        row.listPriceMinor(), row.quantity()));
            }
        }
        if (!toSend.isEmpty()) {
            List<Result> results = offers.setPricesAndStock(u.owner().orgId(), u.id(), u.uploadedBy(), toSend);
            for (Result r : results) {
                done.put(Integer.parseInt(r.rowKey()), fromOffers(Integer.parseInt(r.rowKey()), r, r.atin()));
            }
        }
        return batch.rows().stream().map(r -> done.getOrDefault(r.rowNumber(), new RowResult(r.rowNumber(),
                RowOutcome.FAILED, "offers-service gave no answer for this row. Upload the file again.", null, null,
                null))).toList();
    }

    /**
     * What offers-service said, as the row's outcome. A refusal for the seller's missing selling approval is HELD, not a
     * failure: the seller can be approved, and nothing is for sale meanwhile. An offer waiting for the tax decision is HELD.
     */
    private static RowResult fromOffers(int rowNumber, Result r, String atin) {
        String at = r.atin() != null ? r.atin() : atin;
        return switch (r.outcome()) {
            case "CREATED" -> new RowResult(rowNumber, RowOutcome.OFFER_CREATED, null, at, r.offerId(), null);
            case "UPDATED" -> new RowResult(rowNumber, RowOutcome.UPDATED, null, at, r.offerId(), null);
            case "UNCHANGED" -> new RowResult(rowNumber, RowOutcome.SKIPPED, NO_CHANGE, at, r.offerId(), null);
            case "HELD" -> new RowResult(rowNumber, RowOutcome.HELD, truncate(r.message()), at, r.offerId(), null);
            default -> APPROVAL_CODE.equals(r.code())
                    ? new RowResult(rowNumber, RowOutcome.HELD, truncate(r.message() + " This row is held and is not for "
                            + "sale."), at, null, null)
                    : new RowResult(rowNumber, RowOutcome.FAILED, truncate(r.message()), at, null, null);
        };
    }

    // ------------------------------------------------------------------ saving

    /** Saves the batch unless another run already did (rows no longer pending are left alone). */
    private void save(UUID importId, Batch batch, List<RowResult> results) {
        ProductImport u = imports.lock(importId).orElse(null);
        if (u == null || !u.status().active()) {
            return;
        }
        Set<Integer> pending = new HashSet<>();
        imports.pendingRows(importId, Math.max(OFFER_BATCH, FEED_BATCH) + 1).forEach(r -> pending.add(r.rowNumber()));
        List<RowResult> fresh = results.stream().filter(r -> pending.contains(r.rowNumber())).toList();
        if (fresh.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        imports.setRowResults(importId, fresh);
        List<AuditRow> audit = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int failed = 0;
        int held = 0;
        for (RowResult r : fresh) {
            metrics.row(u.mode().name(), r.outcome().name());
            switch (r.outcome()) {
                case OFFER_CREATED -> {
                    created++;
                    audit.add(new AuditRow(r.rowNumber(), "OFFER_CREATED", r.atin(), null, r.offerId()));
                }
                case UPDATED -> {
                    updated++;
                    audit.add(new AuditRow(r.rowNumber(), "UPDATED", r.atin(), null, r.offerId()));
                }
                case HELD -> {
                    held++;
                    audit.add(new AuditRow(r.rowNumber(), "HELD", r.atin(), null, r.offerId()));
                }
                case FAILED -> failed++;
                default -> {
                }
            }
        }
        imports.audit(importId, u.owner().orgId(), u.uploadedBy(), now, audit);
        imports.update(u.progressed(fresh.size(), 0, updated, failed, created, held, now));
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1000 ? s.substring(0, 999) + "…" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
