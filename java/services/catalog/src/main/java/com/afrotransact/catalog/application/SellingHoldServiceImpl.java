package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductStatus;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SellingHoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class SellingHoldServiceImpl implements SellingHoldService {

    private static final Logger LOG = LoggerFactory.getLogger(SellingHoldServiceImpl.class);

    private final SellingHoldRepository holds;
    private final ProductRepository products;
    private final ProductEvents events;
    private final SellingPublishGate gate;
    private final Clock clock;
    private final int pageSize;
    private final TransactionTemplate tx;

    SellingHoldServiceImpl(SellingHoldRepository holds, ProductRepository products, ProductEvents events,
                           SellingPublishGate gate, Clock clock, PlatformTransactionManager transactions,
                           @Value("${at.catalog.selling-gate.page-size:200}") int pageSize) {
        this.holds = holds;
        this.products = products;
        this.events = events;
        this.gate = gate;
        this.clock = clock;
        this.pageSize = pageSize;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public int pause(Change c) {
        if (c.orgId() == null || c.orgId().isBlank()) {
            return 0;
        }
        int paused = 0;
        UUID after = null;
        while (true) {
            List<UUID> page = holds.liveInScope(c.scopeType(), c.scopeKey(), c.categoryCodes(), c.orgId(), after,
                    pageSize);
            for (UUID id : page) {
                paused += tx.execute(s -> holdOne(id, c)) ? 1 : 0;
            }
            if (page.size() < pageSize) {
                break;
            }
            after = page.getLast();
        }
        LOG.info("Paused {} listing(s) of {} in {} {} ({})", paused, c.orgId(), c.scopeType(), c.scopeKey(), c.reason());
        return paused;
    }

    @Override
    public int restore(Change c) {
        if (c.orgId() == null || c.orgId().isBlank()) {
            return 0;
        }
        return release(c, c.orgId(), false);
    }

    @Override
    public int ruleChanged(Change c) {
        int changed = 0;
        UUID after = null;
        while (true) {
            List<UUID> page = holds.liveInScope(c.scopeType(), c.scopeKey(), c.categoryCodes(), null, after, pageSize);
            for (UUID id : page) {
                changed += tx.execute(s -> holdIfNoLongerAllowed(id, c)) ? 1 : 0;
            }
            if (page.size() < pageSize) {
                break;
            }
            after = page.getLast();
        }
        changed += release(c, null, true);
        LOG.info("Rule change in {} {}: {} listing(s) paused or restored", c.scopeType(), c.scopeKey(), changed);
        return changed;
    }

    /** Pauses one live listing and records why; false when it was not live any more. */
    private boolean holdOne(UUID id, Change c) {
        Product p = products.findById(id).orElse(null);
        if (p == null || p.status() != ProductStatus.LIVE) {
            return false;
        }
        Instant now = Instant.now(clock);
        Product paused = products.update(p.pause(now));
        holds.hold(id, c.scopeType(), c.scopeKey(), p.owner().orgId(), c.reason(), now);
        events.publish(CatalogEvents.PRODUCT_PAUSED, paused);
        return true;
    }

    private boolean holdIfNoLongerAllowed(UUID id, Change c) {
        Product p = products.findById(id).orElse(null);
        if (p == null || p.status() != ProductStatus.LIVE || gate.evaluate(p.owner(), gate.factsOf(p)).sell()) {
            return false;
        }
        return holdOne(id, c);
    }

    /**
     * Releases this scope's holds (one organisation's, or with {@code onlyIfAllowed} everybody's whose seller qualifies
     * again) and resumes the listings that have no other hold.
     */
    private int release(Change c, String orgId, boolean onlyIfAllowed) {
        int resumed = 0;
        UUID after = null;
        while (true) {
            List<UUID> page = holds.heldFor(c.scopeType(), c.scopeKey(), orgId, after, pageSize);
            for (UUID id : page) {
                resumed += tx.execute(s -> releaseOne(id, c, onlyIfAllowed)) ? 1 : 0;
            }
            if (page.size() < pageSize) {
                break;
            }
            after = page.getLast();
        }
        return resumed;
    }

    private boolean releaseOne(UUID id, Change c, boolean onlyIfAllowed) {
        Product p = products.findById(id).orElse(null);
        if (p == null) {
            return false;
        }
        if (onlyIfAllowed && !gate.evaluate(p.owner(), gate.factsOf(p)).sell()) {
            return false;
        }
        boolean last = holds.release(id, c.scopeType(), c.scopeKey());
        if (!last || p.status() != ProductStatus.PAUSED) {
            return false;
        }
        Product resumed = products.update(p.resume(Instant.now(clock)));
        events.publish(CatalogEvents.PRODUCT_RESUMED, resumed);
        return true;
    }
}
