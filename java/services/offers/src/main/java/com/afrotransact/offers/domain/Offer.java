package com.afrotransact.offers.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A seller's offer to sell one catalog variant (ATIN): price, condition, shipping charge, fulfilment and handling.
 * Many sellers can offer the same ATIN; AfroTransact Retail (first party) competes under the same rules.
 *
 * @param atin           the variant ATIN that is sold (one per product for single-variant products)
 * @param productAtin    the ATIN of the product page the variant belongs to
 * @param sellerSku      the seller's own SKU (unique per seller among live offers)
 * @param inventorySku   SKU used by inventory-service for {@link Fulfilment#AFROTRANSACT} stock
 * @param sellerQuantity seller-declared quantity, used only for {@link Fulfilment#SELLER}
 * @param shippingMinor  standard shipping charge per order line added to the price (landed price)
 * @param productLive    false while the catalog product is in review, rejected or paused
 */
public record Offer(UUID id, String atin, String productAtin, UUID productId, String gtin, UUID categoryId,
                    String sellerOrgId, SellerKind sellerKind, String sellerSku, String inventorySku,
                    OfferCondition condition, long priceMinor, Long listPriceMinor, String currency,
                    long shippingMinor, Fulfilment fulfilment, int sellerQuantity, int handlingDays,
                    ShipFrom shipFrom, OfferStatus status, boolean productLive, Instant createdAt, Instant updatedAt,
                    long version) {

    public static final int MAX_HANDLING_DAYS = 30;
    public static final int MAX_SKU = 40;
    private static final Pattern ATIN = Pattern.compile("AT[0-9A-Z]{8}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public Offer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sellerKind, "sellerKind");
        Objects.requireNonNull(status, "status");
        Map<String, String> errors = new LinkedHashMap<>();
        if (atin == null || !ATIN.matcher(atin).matches()) {
            errors.put("atin", "Must be an ATIN (AT + 8 letters or digits)");
        }
        if (productAtin == null || !ATIN.matcher(productAtin).matches()) {
            errors.put("productAtin", "Must be an ATIN");
        }
        if (sellerOrgId == null || sellerOrgId.isBlank()) {
            errors.put("sellerOrgId", "Required");
        }
        if (sellerSku == null || sellerSku.isBlank() || sellerSku.length() > MAX_SKU) {
            errors.put("sellerSku", "Required, at most " + MAX_SKU + " characters");
        }
        if (inventorySku == null || inventorySku.isBlank() || inventorySku.length() > MAX_SKU) {
            errors.put("inventorySku", "Required, at most " + MAX_SKU + " characters");
        }
        if (condition == null) {
            errors.put("condition", "Required");
        }
        if (fulfilment == null) {
            errors.put("fulfilment", "Required");
        }
        if (priceMinor <= 0) {
            errors.put("priceMinor", "Must be greater than zero");
        }
        if (listPriceMinor != null && listPriceMinor < priceMinor) {
            errors.put("listPriceMinor", "List price cannot be lower than the price");
        }
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            errors.put("currency", "Must be an ISO 4217 code such as USD");
        }
        if (shippingMinor < 0) {
            errors.put("shippingMinor", "Cannot be negative");
        }
        if (sellerQuantity < 0) {
            errors.put("quantity", "Cannot be negative");
        }
        if (handlingDays < 0 || handlingDays > MAX_HANDLING_DAYS) {
            errors.put("handlingDays", "Between 0 and " + MAX_HANDLING_DAYS + " days");
        }
        if (!errors.isEmpty()) {
            throw new OfferRuleViolation("The offer is not valid", errors);
        }
    }

    /** Price plus the shipping charge: what the shopper pays for one unit delivered. */
    public long landedPriceMinor() {
        return priceMinor + shippingMinor;
    }

    public boolean live() {
        return status == OfferStatus.ACTIVE && productLive;
    }

    /** Replaces the commercial terms; not allowed once withdrawn. */
    public Offer revise(Terms terms, Instant now) {
        if (status == OfferStatus.WITHDRAWN) {
            throw new IllegalStateException("A withdrawn offer cannot be changed");
        }
        return new Offer(id, atin, productAtin, productId, gtin, categoryId, sellerOrgId, sellerKind, sellerSku,
                terms.inventorySku(), terms.condition(), terms.priceMinor(), terms.listPriceMinor(), currency,
                terms.shippingMinor(), terms.fulfilment(), terms.sellerQuantity(), terms.handlingDays(),
                terms.shipFrom(), status, productLive, createdAt, now, version);
    }

    public Terms terms() {
        return new Terms(condition, priceMinor, listPriceMinor, shippingMinor, fulfilment, sellerQuantity,
                handlingDays, shipFrom, inventorySku);
    }

    public Offer pause(Instant now) {
        if (status != OfferStatus.ACTIVE) {
            throw new IllegalStateException("Only an active offer can be paused");
        }
        return withStatus(OfferStatus.PAUSED, now);
    }

    public Offer resume(Instant now) {
        if (status != OfferStatus.PAUSED) {
            throw new IllegalStateException("Only a paused offer can be resumed");
        }
        return withStatus(OfferStatus.ACTIVE, now);
    }

    public Offer withdraw(Instant now) {
        if (status == OfferStatus.WITHDRAWN) {
            throw new IllegalStateException("The offer is already withdrawn");
        }
        return withStatus(OfferStatus.WITHDRAWN, now);
    }

    public Offer withProductLive(boolean live, Instant now) {
        return new Offer(id, atin, productAtin, productId, gtin, categoryId, sellerOrgId, sellerKind, sellerSku,
                inventorySku, condition, priceMinor, listPriceMinor, currency, shippingMinor, fulfilment,
                sellerQuantity, handlingDays, shipFrom, status, live, createdAt, now, version);
    }

    private Offer withStatus(OfferStatus next, Instant now) {
        return new Offer(id, atin, productAtin, productId, gtin, categoryId, sellerOrgId, sellerKind, sellerSku,
                inventorySku, condition, priceMinor, listPriceMinor, currency, shippingMinor, fulfilment,
                sellerQuantity, handlingDays, shipFrom, next, productLive, createdAt, now, version);
    }

    /** The seller-editable commercial terms of an offer. */
    public record Terms(OfferCondition condition, long priceMinor, Long listPriceMinor, long shippingMinor,
                        Fulfilment fulfilment, int sellerQuantity, int handlingDays, ShipFrom shipFrom,
                        String inventorySku) {
    }
}
