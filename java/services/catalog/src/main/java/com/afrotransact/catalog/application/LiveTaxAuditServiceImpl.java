package com.afrotransact.catalog.application;

import java.util.List;

import com.afrotransact.catalog.domain.CategoryNode;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.tax.TaxReadiness;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.platform.security.PermissionChecker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LiveTaxAuditServiceImpl implements LiveTaxAuditService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final PermissionChecker permissions;

    LiveTaxAuditServiceImpl(ProductRepository products, CategoryRepository categories, PermissionChecker permissions) {
        this.products = products;
        this.categories = categories;
        this.permissions = permissions;
    }

    @Override
    @Transactional(readOnly = true)
    public Audit liveWithoutTaxDecision(int page, int size) {
        permissions.require(CatalogPermissions.TAX_READ);
        int pageNo = Math.max(page, 0);
        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        long total = products.countLiveWithoutTaxDecision();
        List<Item> items = products.findLiveWithoutTaxDecision(pageNo * limit, limit).stream().map(this::item).toList();
        return new Audit(items, total, pageNo, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        permissions.require(CatalogPermissions.TAX_READ);
        return products.countLiveWithoutTaxDecision();
    }

    private Item item(Product p) {
        TaxReadiness readiness = TaxReadiness.of(p, null, null);
        List<VariantItem> variants = readiness.variants().stream()
                .map(v -> new VariantItem(v.atin(), v.sellerSku(), v.status().name(),
                        v.reason() == null ? "No tax decision is stored for this variant." : v.reason()))
                .toList();
        String category = p.categoryId() == null ? null
                : categories.findById(p.categoryId()).map(CategoryNode::path).orElse(null);
        return new Item(p.id(), p.atin().value(), p.title(), p.owner().orgId(), category, p.status().name(),
                p.updatedAt(), variants);
    }
}
