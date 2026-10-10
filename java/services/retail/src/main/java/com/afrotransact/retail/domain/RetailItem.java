package com.afrotransact.retail.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Product master of a first-party (AfroTransact Retail) item: SKU and GTIN issued from the GS1 prefix, link to the
 * catalog variant ATIN, customs data (HTS code, duty rate), shelf life and storage, pack sizes, supplier SKUs and
 * costs, reorder point, selling price and listing readiness.
 */
public record RetailItem(UUID id, String sku, long itemReference, String gtin, String name, String category,
                         String atin, UUID primarySupplierId, List<SupplierItem> supplierItems, String originCountry,
                         String hsCode, BigDecimal dutyRatePercent, int shelfLifeDays, StorageType storage,
                         int reorderPoint, String packSize, int innerPack, int casePack, int unitWeightGrams,
                         long standardCostMinor, long priceMinor, String currency, ListingStatus listingStatus,
                         Instant createdAt, Instant updatedAt, long version) {

    private static final Set<String> SKIP_WORDS = Set.of("AFROTRANSACT", "BRAND", "THE");

    public RetailItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(listingStatus, "listingStatus");
        Objects.requireNonNull(storage, "storage");
        if (name == null || name.trim().length() < 3 || name.length() > 200) {
            throw new RetailRuleViolation("retail.item-invalid", "Name must be 3–200 characters");
        }
        if (hsCode == null || !hsCode.matches("\\d{4}(\\.?\\d{2}){1,3}")) {
            throw new RetailRuleViolation("retail.item-invalid", "HTS code must look like 0910.91.0000");
        }
        dutyRatePercent = dutyRatePercent == null ? BigDecimal.ZERO : dutyRatePercent;
        if (dutyRatePercent.signum() < 0 || dutyRatePercent.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new RetailRuleViolation("retail.item-invalid", "Duty rate must be 0–100%");
        }
        if (shelfLifeDays < 1 || shelfLifeDays > 3650) {
            throw new RetailRuleViolation("retail.item-invalid", "Shelf life must be 1–3650 days");
        }
        if (reorderPoint < 0) {
            throw new RetailRuleViolation("retail.item-invalid", "Reorder point cannot be negative");
        }
        if (innerPack < 1 || casePack < 1 || casePack % innerPack != 0) {
            throw new RetailRuleViolation("retail.item-invalid",
                    "Case pack must be a whole number of inner packs (both at least 1)");
        }
        if (unitWeightGrams < 0 || standardCostMinor < 0 || priceMinor < 0) {
            throw new RetailRuleViolation("retail.item-invalid", "Weight, cost and price cannot be negative");
        }
        if (originCountry == null || !originCountry.matches("[A-Z]{2}")) {
            throw new RetailRuleViolation("retail.item-invalid", "Origin must be an ISO 3166 alpha-2 code");
        }
        currency = Currencies.require(currency);
        supplierItems = supplierItems == null ? List.of() : List.copyOf(supplierItems);
        Objects.requireNonNull(primarySupplierId, "primarySupplierId");
        if (supplierItems.stream().noneMatch(s -> s.supplierId().equals(primarySupplierId))) {
            throw new RetailRuleViolation("retail.item-invalid",
                    "The primary supplier needs a supplier SKU and cost on the item");
        }
        if (supplierItems.stream().map(SupplierItem::supplierId).distinct().count() != supplierItems.size()) {
            throw new RetailRuleViolation("retail.item-invalid", "One supplier SKU per supplier");
        }
    }

    /** {@code AT-<3 letters>-<item reference>}, e.g. AT-JOL-200061 for "AfroTransact Brand Jollof Seasoning". */
    public static String skuFor(String name, long itemReference) {
        String code = "ITM";
        for (String word : name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z ]", " ").trim().split("\\s+")) {
            if (word.length() >= 3 && !SKIP_WORDS.contains(word)) {
                code = word.substring(0, 3);
                break;
            }
        }
        return "AT-" + code + "-" + itemReference;
    }

    public Optional<SupplierItem> supplierItem(UUID supplierId) {
        return supplierItems.stream().filter(s -> s.supplierId().equals(supplierId)).findFirst();
    }

    /** Minimum remaining life at receipt: max(minDays, fraction × shelf life), else the lot is quarantined. */
    public int minRemainingLifeDays(int minDays, double fraction) {
        int share = BigDecimal.valueOf(shelfLifeDays).multiply(BigDecimal.valueOf(fraction))
                .setScale(0, RoundingMode.HALF_UP).intValue();
        return Math.max(minDays, share);
    }

    public RetailItem linkAtin(String newAtin, Instant now) {
        if (newAtin == null || !newAtin.matches("AT[0-9A-Z]{8}")) {
            throw new RetailRuleViolation("retail.atin-invalid", "ATIN must look like AT + 8 letters or digits");
        }
        return with(newAtin, listingStatus, now);
    }

    public RetailItem changeListing(ListingStatus target, Instant now) {
        boolean allowed = switch (listingStatus) {
            case DRAFT -> target == ListingStatus.LIVE;
            case LIVE -> target == ListingStatus.PAUSED;
            case PAUSED -> target == ListingStatus.LIVE;
        };
        if (!allowed) {
            throw new RetailStateConflict("retail.listing-invalid-transition",
                    "Listing of " + sku + " cannot move from " + listingStatus + " to " + target);
        }
        if (target == ListingStatus.LIVE && (atin == null || priceMinor <= 0)) {
            throw new RetailRuleViolation("retail.listing-incomplete",
                    "Link the catalog ATIN and set a price before the listing goes live");
        }
        return with(atin, target, now);
    }

    /** Updates commercial and logistics data; identity (SKU, GTIN, ATIN) and listing status are unchanged. */
    public RetailItem revise(String newName, String newCategory, UUID newPrimarySupplier,
                             List<SupplierItem> newSupplierItems, String newHsCode, BigDecimal newDuty,
                             int newShelfLife, StorageType newStorage, int newReorder, String newPackSize,
                             int newInnerPack, int newCasePack, int newWeight, long newCost, long newPrice,
                             Instant now) {
        return new RetailItem(id, sku, itemReference, gtin, newName, newCategory, atin, newPrimarySupplier,
                newSupplierItems, originCountry, newHsCode, newDuty, newShelfLife, newStorage, newReorder,
                newPackSize, newInnerPack, newCasePack, newWeight, newCost, newPrice, currency, listingStatus,
                createdAt, now, version);
    }

    private RetailItem with(String newAtin, ListingStatus newStatus, Instant now) {
        return new RetailItem(id, sku, itemReference, gtin, name, category, newAtin, primarySupplierId,
                supplierItems, originCountry, hsCode, dutyRatePercent, shelfLifeDays, storage, reorderPoint, packSize,
                innerPack, casePack, unitWeightGrams, standardCostMinor, priceMinor, currency, newStatus, createdAt,
                now, version);
    }
}
