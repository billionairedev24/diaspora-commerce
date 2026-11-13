package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.ProductTaxHoldRepository;
import com.afrotransact.catalog.port.ProductTaxHoldRepository.Held;
import com.afrotransact.catalog.port.TaxDecisionAuthority;
import com.afrotransact.platform.security.PermissionChecker;
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
    private final TaxAuthorityCheck authority;
    private final CategoryRepository categories;
    private final PermissionChecker permissions;
    private final Duration authorityInterval;
    private volatile Instant lastAuthorityPass = Instant.EPOCH;

    TaxGateSweepServiceImpl(ProductRepository products, ProductTaxHoldRepository holds, ProductLiveGate liveGate,
                            ProductEvents events, Clock clock, PlatformTransactionManager transactions,
                            TaxAuthorityCheck authority, CategoryRepository categories, PermissionChecker permissions,
                            @Value("${at.catalog.tax-gate.sweep-page-size:200}") int pageSize,
                            @Value("${at.catalog.tax-gate.authority-interval:5m}") Duration authorityInterval) {
        this.authority = authority;
        this.categories = categories;
        this.permissions = permissions;
        this.authorityInterval = authorityInterval;
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
        boolean due = Duration.between(lastAuthorityPass, Instant.now(clock)).compareTo(authorityInterval) >= 0;
        return run(due).changed();
    }

    @Override
    public Result sweepNow() {
        return run(true);
    }

    @Override
    public Result sweepNowAsStaff() {
        permissions.require(CatalogPermissions.TAX_CLASSIFY_ADMIN);
        return run(true);
    }

    private Result run(boolean askTaxReturns) {
        int[] skipped = new int[1];
        int closed = closeOffenders(skipped);
        AuthorityPass pass = askTaxReturns ? againstTaxReturns() : new AuthorityPass(0, 0, true);
        int reopened = reopenResolved(skipped);
        int announced = announceClosed();
        Result result = new Result(closed + pass.closed(), pass.closed(), reopened, announced, pass.checked(),
                pass.reachable(), skipped[0]);
        if (skipped[0] > 0) {
            LOG.warn("Tax gate sweep: media unavailable, {} held-photo checks skipped (those listings stay as they were)",
                    skipped[0]);
        }
        if (result.changed() > 0) {
            LOG.info("Tax gate sweep: {} closed ({} because tax-returns is not RESOLVED), {} back on sale, {} announced,"
                    + " {} live listings checked against tax-returns", result.closed(), result.closedByTaxReturns(),
                    reopened, announced, pass.checked());
        }
        return result;
    }

    private record AuthorityPass(int checked, int closed, boolean reachable) {
    }

    /**
     * Every LIVE listing, a page at a time: one batched question to tax-returns per page. A listing whose variants are not
     * all RESOLVED there is closed, whatever catalog's own copy says. A variant tax-returns has no record of gets its
     * facts re-reported so it shows up in the Tax review queue. Unreachable: stop, keep every state, mark nobody OK.
     */
    private AuthorityPass againstTaxReturns() {
        if (!authority.consulted()) {
            return new AuthorityPass(0, 0, true);
        }
        int checked = 0;
        int closed = 0;
        UUID after = null;
        while (true) {
            List<Product> page = products.findLiveAfter(after, pageSize);
            if (page.isEmpty()) {
                break;
            }
            Map<UUID, TaxAuthorityCheck.Verdict> verdicts;
            try {
                verdicts = authority.verdicts(page);
            } catch (TaxDecisionAuthority.Unavailable ex) {
                return new AuthorityPass(checked, closed, false);
            }
            for (Product p : page) {
                TaxAuthorityCheck.Verdict v = verdicts.get(p.id());
                if (v == null || v.resolved()) {
                    continue;
                }
                Product stored = tx.execute(s -> liveGate.closeForTaxReturns(p.id(), v.reasonCode()));
                if (stored != null && stored.status() != ProductStatus.LIVE) {
                    closed++;
                }
                // Re-report the facts: tax-returns creates a missing review record and learns the product's title, so the
                // Tax review queue shows a name staff can recognise. Idempotent there.
                tx.executeWithoutResult(s -> reportFacts(p));
            }
            checked += page.size();
            after = page.getLast().id();
            if (page.size() < pageSize) {
                break;
            }
        }
        lastAuthorityPass = Instant.now(clock);
        return new AuthorityPass(checked, closed, true);
    }

    /** tax-returns learns a variant's facts only from this event; re-sending it creates the missing review record. */
    private void reportFacts(Product p) {
        if (p.categoryId() == null) {
            return;
        }
        CategoryNode node = categories.findById(p.categoryId()).orElse(null);
        events.publishTaxFactsChanged(p, node, null, List.of(), Set.of());
    }

    private int closeOffenders(int[] skipped) {
        int closed = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<Product> offenders = products.findLiveWithoutTaxDecision(0, pageSize);
            if (offenders.isEmpty()) {
                break;
            }
            for (Product p : offenders) {
                ProductLiveGate.Reconciliation done = tx.execute(s -> liveGate.reconcileChecked(p.id()));
                Product after = done == null ? null : done.product();
                if (done != null && done.photoCheckSkipped()) {
                    skipped[0]++;
                }
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

    private int reopenResolved(int[] skipped) {
        int reopened = 0;
        UUID after = null;
        while (true) {
            List<UUID> page = holds.openHolds(after, pageSize);
            for (UUID id : page) {
                ProductLiveGate.Reconciliation done = tx.execute(s -> liveGate.reconcileChecked(id));
                Product result = done == null ? null : done.product();
                if (done != null && done.photoCheckSkipped()) {
                    skipped[0]++;
                }
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
