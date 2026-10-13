package com.afrotransact.catalog.application;

import com.afrotransact.catalog.port.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ListingStockSyncServiceImpl implements ListingStockSyncService {

    private final ProductRepository products;

    ListingStockSyncServiceImpl(ProductRepository products) {
        this.products = products;
    }

    @Override
    @Transactional
    public boolean mirror(String ownerOrg, String variantAtin, int quantity) {
        return products.mirrorVariantQuantity(ownerOrg, variantAtin, Math.max(0, quantity));
    }
}
