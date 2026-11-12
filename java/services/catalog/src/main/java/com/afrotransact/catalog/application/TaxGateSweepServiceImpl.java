package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductTaxHoldRepository;
import com.afrotransact.catalog.port.ProductTaxHoldRepository.Held;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class TaxGateSweepServiceImpl implements TaxGateSweepService {

    private static final Logger LOG = LoggerFactory.getLogger(TaxGateSweepServiceImpl.class);
    /** Pages of offenders closed per run; a closed product leaves the set, so each page starts at 0. */
    private static final int MAX_PAGES = 10;

    private final ProductRepository products;
    private final ProductTaxHoldRepository holds;
    private final ProductLiveGate liveGate;
    private final ProductEvents events;
    private final Clock clock;
    private final int pageSize;
    private final TransactionTemplate tx;

    TaxGateSweepServiceImpl(ProductRepository products, ProductTaxHoldRepository holds, ProductLiveGate liveGate,
                            ProductEvents events, Clock clock, PlatformTransactionManager transactions,
                            @Value("${at.catalog.tax-gate.sweep-page-size:200}") int pageSize) {
        this.products = products;
        this.holds = holds;
        this.liveGate = liveGate;
        this.events = events;
        this.clock = clock;
        this.pageSize = pageSize;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public int sweep() {
        int closed = closeOffenders();
        int reopened = reopenResolved();
        int announced = announceClosed();
        if (closed + reopened + announced > 0) {
            LOG.info("Tax gate sweep: {} closed, {} back on sale, {} announced", closed, reopened, announced);
        }
        return closed + reopened + announced;
    }

    private int closeOffenders() {
        int closed = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Product> offenders = products.findLiveWithoutTaxDecision(0, pageSize);
            if (offenders.isEmpty()) {
                break;
            }
            for (Product p : offenders) {
                Product after = tx.execute(s -> liveGate.reconcile(p.id()));
                if (after != null && after.status() != ProductStatus.LIVE) {
                    closed++;
                }
            }
            if (offenders.size() < pageSize) {
                break;
            }
        }
        return closed;
    }

    private int reopenResolved() {
        int reopened = 0;
        UUID after = null;
        while (true) {
            List<UUID> page = holds.openHolds(after, pageSize);
            for (UUID id : page) {
                Product result = tx.execute(s -> liveGate.reconcile(id));
                if (result != null && result.status() == ProductStatus.LIVE) {
                    reopened++;
                }
            }
            if (page.size() < pageSize) {
                return reopened;
            }
            after = page.getLast();
        }
    }

    /** Publishes catalog.product.paused for listings the migration closed, so search and offers drop them. */
    private int announceClosed() {
        int announced = 0;
        for (Held held : holds.unannounced(pageSize)) {
            Boolean done = tx.execute(s -> {
                Product p = products.findById(held.productId()).orElse(null);
                Instant now = Instant.now(clock);
                if (p != null && p.status() != ProductStatus.LIVE) {
                    events.publishWithReason(CatalogEvents.PRODUCT_PAUSED, p, held.reasonCode());
                }
                holds.markAnnounced(held.productId(), now);
                return p != null;
            });
            if (Boolean.TRUE.equals(done)) {
                announced++;
            }
        }
        return announced;
    }
}
