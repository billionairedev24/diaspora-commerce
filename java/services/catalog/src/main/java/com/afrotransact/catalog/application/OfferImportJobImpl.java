package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.imports.ImportMode;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.port.OfferWriter.OffersUnavailable;
import com.afrotransact.catalog.port.ProductImportRepository;
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

    private final ProductImportRepository imports;
    private final OfferRowProcessor rows;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final TransactionTemplate tx;

    OfferImportJobImpl(ProductImportRepository imports, OfferRowProcessor rows, DomainEventPublisher events, Clock clock,
                       PlatformTransactionManager transactions) {
        this.imports = imports;
        this.rows = rows;
        this.events = events;
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
                List<RowResult> results = batch.upload().mode() == ImportMode.PRICE_QTY
                        ? rows.feed(batch.upload(), batch.rows()) : rows.offers(batch.upload(), batch.rows());
                rows.save(importId, results);
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
        List<StoredRow> pending = imports.pendingRows(importId, u.mode() == ImportMode.PRICE_QTY ? FEED_BATCH
                : OFFER_BATCH);
        if (pending.isEmpty()) {
            ProductImport done = imports.update(u.completed(now));
            int skipped = (int) imports.rows(importId, null, RowOutcome.SKIPPED, 0, 1).total();
            events.publish(CatalogEvents.IMPORT_COMPLETED, CatalogEvents.AGGREGATE_IMPORT, done.id().toString(),
                    ImportCompletedPayload.of(done, skipped));
            LOG.info("Bulk {} upload {} done: {} offers created, {} changed, {} held, {} failed, {} skipped",
                    done.mode(), done.reference(), done.offersCreated(), done.updatedProducts(), done.heldRows(),
                    done.failedRows(), skipped);
            return null;
        }
        return new Batch(imports.update(u.running(now)), pending);
    }
}
