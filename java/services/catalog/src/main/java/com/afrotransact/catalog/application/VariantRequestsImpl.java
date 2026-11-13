package com.afrotransact.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.afrotransact.catalog.domain.Atin;
import com.afrotransact.catalog.domain.BusinessDays;
import com.afrotransact.catalog.domain.CatalogPolicy;
import com.afrotransact.catalog.domain.ChangeRequestPolicy;
import com.afrotransact.catalog.domain.ChangeRequestType;
import com.afrotransact.catalog.domain.Product;
import com.afrotransact.catalog.domain.ProductChangeRequest;
import com.afrotransact.catalog.domain.ProductType;
import com.afrotransact.catalog.domain.VariationDimension;
import com.afrotransact.catalog.domain.VariationTheme;
import com.afrotransact.catalog.domain.imports.ImportColumns;
import com.afrotransact.catalog.domain.imports.ProductImport;
import com.afrotransact.catalog.domain.imports.RowOutcome;
import com.afrotransact.catalog.domain.imports.StoredRow;
import com.afrotransact.catalog.port.CategoryRepository;
import com.afrotransact.catalog.port.ChangeRequestRepository;
import com.afrotransact.catalog.port.ProductImportRepository.RowResult;
import com.afrotransact.catalog.port.ProductRepository;
import com.afrotransact.catalog.port.SellerOffers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class VariantRequestsImpl implements VariantRequests {

    private static final String SENT = "This size or colour is not on the page yet. We sent it for review.";

    private final ProductRepository products;
    private final ChangeRequestRepository requests;
    private final CategoryRepository categories;
    private final CatalogSettingsService settings;
    private final SellerOffers sellerOffers;
    private final ChangeRequestEvents events;
    private final Clock clock;

    VariantRequestsImpl(ProductRepository products, ChangeRequestRepository requests, CategoryRepository categories,
                        CatalogSettingsService settings, SellerOffers sellerOffers, ChangeRequestEvents events,
                        Clock clock) {
        this.products = products;
        this.requests = requests;
        this.categories = categories;
        this.settings = settings;
        this.sellerOffers = sellerOffers;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RowResult file(ProductImport u, StoredRow row, String parentAtin) {
        String org = u.owner().orgId();
        Map<String, Object> options = optionsOf(u, row);
        if (options.isEmpty()) {
            return failed(row, "Say which size or colour this row is, in the Options column, so we can ask for it.");
        }
        Product page = parentAtin == null ? null : pageOf(parentAtin);
        if (page == null) {
            return failed(row, "We could not find the page the other sizes or colours of this product are on.");
        }
        // offers-service not answering throws: the row stays pending and the upload is resumed.
        if (!sellerOffers.hasOpenOffer(org, page.atin().value())) {
            return failed(row, "Add your offer to this product first. Then upload this row again to ask for the new "
                    + "size or colour.");
        }
        String sku = row.sku() == null ? "" : row.sku().strip().toUpperCase(Locale.ROOT);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("options", options);
        if (!sku.isEmpty()) {
            payload.put("sellerSku", sku);
        }
        CatalogPolicy policy = settings.current();
        Map<String, String> errors = ChangeRequestPolicy.problems(ChangeRequestType.NEW_VARIANT, payload, null,
                policy.noteMaxLength(), page.atin().value());
        if (!errors.isEmpty()) {
            return failed(row, String.join(". ", errors.values()) + ".");
        }
        // One waiting request per SKU: the field of a NEW_VARIANT request is the SKU, so several children of one page
        // can be asked for in one file, and the same file again does not ask twice.
        if (!sku.isEmpty() && requests.existsOpen(page.id(), ChangeRequestType.NEW_VARIANT, sku, org)) {
            return new RowResult(row.rowNumber(), RowOutcome.HELD, SENT, page.atin().value(), null, null);
        }
        Instant now = Instant.now(clock);
        ProductChangeRequest filed = ProductChangeRequest.filed(page.id(), page.atin().value(), null, null,
                ChangeRequestType.NEW_VARIANT, sku, payload, "Sent by a bulk upload (" + u.reference() + ")", org,
                u.uploadedBy(), now, BusinessDays.add(now, policy.reviewSlaBusinessDays()));
        try {
            ProductChangeRequest saved = requests.insert(filed);
            events.created(saved, page, now);
        } catch (DuplicateKeyException ex) {
            return new RowResult(row.rowNumber(), RowOutcome.HELD, SENT, page.atin().value(), null, null);
        }
        return new RowResult(row.rowNumber(), RowOutcome.HELD, SENT, page.atin().value(), null, null);
    }

    private Product pageOf(String atinText) {
        Atin atin = new Atin(atinText);
        return products.findByAtin(atin).or(() -> products.findByVariantAtin(atin)).orElse(null);
    }

    /** The size or colour the row names: the Options pairs ("Size: M; Color: Gold") and the variation columns of its type. */
    private Map<String, Object> optionsOf(ProductImport u, StoredRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> header = u.header();
        ProductType type = u.productTypeCode() == null ? null : categories.findProductType(u.productTypeCode())
                .orElse(null);
        for (int i = 0; i < header.size() && i < row.cells().size(); i++) {
            String cell = row.cells().get(i) == null ? "" : row.cells().get(i).strip();
            if (cell.isEmpty()) {
                continue;
            }
            String h = ImportColumns.normalise(header.get(i));
            if (h.equals("options")) {
                for (String pair : cell.split(";")) {
                    int colon = pair.indexOf(':');
                    if (colon > 0 && colon < pair.length() - 1) {
                        out.put(pair.substring(0, colon).strip(), pair.substring(colon + 1).strip());
                    }
                }
            } else if (type != null && isDimension(type, header.get(i))) {
                out.put(header.get(i).strip(), cell);
            }
        }
        return out;
    }

    private static boolean isDimension(ProductType type, String header) {
        for (VariationTheme theme : type.themes()) {
            for (VariationDimension d : theme.dimensions()) {
                if (d.name().equalsIgnoreCase(header.strip())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static RowResult failed(StoredRow row, String why) {
        return new RowResult(row.rowNumber(), RowOutcome.FAILED, why, null, null, null);
    }
}
