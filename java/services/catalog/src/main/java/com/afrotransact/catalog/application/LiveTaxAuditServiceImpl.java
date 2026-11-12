package com.afrotransact.catalog.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.tax.LiveRequiresTax;
import com.afrotransact.catalog.domain.tax.TaxReadiness;
import com.afrotransact.catalog.domain.tax.TaxStatus;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.TaxDecisionAuthority;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LiveTaxAuditServiceImpl implements LiveTaxAuditService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int SCAN_PAGE = 200;
    private static final int MAX_LISTED = 2000;
    private static final long CACHE_MILLIS = 20_000;

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final PermissionChecker permissions;
    private final TaxAuthorityCheck authority;
    private volatile Snapshot cached = new Snapshot(0, List.of(), false);

    LiveTaxAuditServiceImpl(ProductRepository products, CategoryRepository categories, PermissionChecker permissions,
                            TaxAuthorityCheck authority) {
        this.products = products;
        this.categories = categories;
        this.permissions = permissions;
        this.authority = authority;
    }

    /** One scan of the live listings (kept 20 s: the staff banner polls it). */
    private record Snapshot(long takenAt, List<Failing> failing, boolean authorityChecked) {
    }

    private record Failing(Product product, TaxAuthorityCheck.Verdict verdict) {
    }

    @Override
    @Transactional(readOnly = true)
    public Audit liveWithoutTaxDecision(int page, int size) {
        permissions.require(CatalogPermissions.TAX_READ);
        int pageNo = Math.max(page, 0);
        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        Snapshot snap = scan();
        int from = Math.min(pageNo * limit, snap.failing().size());
        int to = Math.min(from + limit, snap.failing().size());
        List<Item> items = snap.failing().subList(from, to).stream().map(this::item).toList();
        return new Audit(items, snap.failing().size(), pageNo, limit, snap.authorityChecked());
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        permissions.require(CatalogPermissions.TAX_READ);
        return products.countLiveWithoutTaxDecision();
    }

    /**
     * Every live listing, page by page: failing when catalog's own check fails OR tax-returns does not answer RESOLVED
     * for every variant. When tax-returns cannot be asked the scan falls back to catalog's own check and says so.
     */
    private Snapshot scan() {
        Snapshot now = cached;
        if (System.currentTimeMillis() - now.takenAt() < CACHE_MILLIS) {
            return now;
        }
        List<Failing> failing = new ArrayList<>();
        boolean checked = authority.consulted();
        UUID after = null;
        while (failing.size() < MAX_LISTED) {
            List<Product> live = products.findLiveAfter(after, SCAN_PAGE);
            if (live.isEmpty()) {
                break;
            }
            Map<UUID, TaxAuthorityCheck.Verdict> verdicts = Map.of();
            if (checked) {
                try {
                    verdicts = authority.verdicts(live);
                } catch (TaxDecisionAuthority.Unavailable ex) {
                    checked = false;
                }
            }
            for (Product p : live) {
                TaxAuthorityCheck.Verdict v = verdicts.get(p.id());
                if (!LiveRequiresTax.holds(p) || v != null && !v.resolved()) {
                    failing.add(new Failing(p, v));
                }
            }
            after = live.getLast().id();
            if (live.size() < SCAN_PAGE) {
                break;
            }
        }
        Snapshot next = new Snapshot(System.currentTimeMillis(), List.copyOf(failing), checked);
        cached = next;
        return next;
    }

    private Item item(Failing f) {
        Product p = f.product();
        TaxReadiness readiness = TaxReadiness.of(p, null, null);
        List<VariantItem> variants = new ArrayList<>();
        for (TaxReadiness.VariantReadiness v : readiness.variants()) {
            TaxDecisionAuthority.Answer a = f.verdict() == null ? null : f.verdict().variants().get(v.atin());
            boolean taxReturnsNo = f.verdict() != null && (a == null || !a.resolved());
            if (taxReturnsNo) {
                String status = a == null || a.decisionStatus() == null ? "NO_DECISION" : a.decisionStatus();
                // Catalog's own status words, so staff screens label it ("Waiting for tax review"): tax-returns'
                // NEEDS_APPROVAL means the record waits for a reviewer.
                String shown = "NEEDS_APPROVAL".equals(status) ? TaxStatus.PENDING_REVIEW.name() : status;
                variants.add(new VariantItem(v.atin(), v.sellerSku(), shown, TaxAuthorityCheck.plain(status)));
            } else {
                variants.add(new VariantItem(v.atin(), v.sellerSku(), v.status().name(),
                        v.reason() == null ? "No tax decision is stored for this variant." : v.reason()));
            }
        }
        String category = p.categoryId() == null ? null
                : categories.findById(p.categoryId()).map(CategoryNode::path).orElse(null);
        return new Item(p.id(), p.atin().value(), p.title(), p.owner().orgId(), category, p.status().name(),
                p.updatedAt(), variants);
    }
}
