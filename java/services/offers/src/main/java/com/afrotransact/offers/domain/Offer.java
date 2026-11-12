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
 * @param conditionNote  what is different about a used or renewed item (null for new)
 * @param packaging      the box this seller ships it in, only for Fulfilled by AfroTransact (S383); null otherwise
 * @param sellerBehaviour the seller's own answers about how the item is sold (the tax behaviour facts), kept so the tax
 *                       decision can be asked again when it is still missing (S384)
 * @param heldReason     why a TAX_HELD offer waits (TAX_DECISION_MISSING, TAX_UNAVAILABLE ...), else null
 * @param taxBasis       why the offer may be live as far as tax goes: {@link #TAX_BASIS_OWNER_PAGE} (the offer of the page's
 *                       owner, covered by the page's own gate) or {@link #TAX_BASIS_SELLER_DECISION} (tax-returns holds a
 *                       RESOLVED decision for this seller on this ATIN); null means not verified yet, and an unverified
 *                       offer is never live (S384)
 */
public record Offer(UUID id, String atin, String productAtin, UUID productId, String gtin, UUID categoryId,
                    String sellerOrgId, SellerKind sellerKind, String sellerSku, String inventorySku,
                    OfferCondition condition, long priceMinor, Long listPriceMinor, String currency,
                    long shippingMinor, Fulfilment fulfilment, int sellerQuantity, int handlingDays,
                    ShipFrom shipFrom, OfferStatus status, boolean productLive, Instant createdAt, Instant updatedAt,
                    long version, String conditionNote, OfferPackage packaging, Map<String, Object> sellerBehaviour,
                    String heldReason, String taxBasis) {

    /** The offer of the page's owner: the page's own tax decision and live gate cover it. */
    public static final String TAX_BASIS_OWNER_PAGE = "OWNER_PAGE";
    /** tax-returns holds a stored, RESOLVED decision for this seller on this ATIN. */
    public static final String TAX_BASIS_SELLER_DECISION = "SELLER_DECISION";
    /** The seller has no RESOLVED decision on this ATIN yet. */
    public static final String HOLD_DECISION_MISSING = "TAX_DECISION_MISSING";
    /** tax-returns could not be asked: the offer waits rather than going live unchecked. */
    public static final String HOLD_UNAVAILABLE = "TAX_UNAVAILABLE";
    /** A paused offer without a verified basis came back: it waits for the check. */
    public static final String HOLD_UNVERIFIED = "TAX_DECISION_UNVERIFIED";

    /** An offer without condition note, package, behaviour or tax basis (not verified: never live until it is). */
    public Offer(UUID id, String atin, String productAtin, UUID productId, String gtin, UUID categoryId,
                 String sellerOrgId, SellerKind sellerKind, String sellerSku, String inventorySku,
                 OfferCondition condition, long priceMinor, Long listPriceMinor, String currency, long shippingMinor,
                 Fulfilment fulfilment, int sellerQuantity, int handlingDays, ShipFrom shipFrom, OfferStatus status,
                 boolean productLive, Instant createdAt, Instant updatedAt, long version) {
        this(id, atin, productAtin, productId, gtin, categoryId, sellerOrgId, sellerKind, sellerSku, inventorySku,
                condition, priceMinor, listPriceMinor, currency, shippingMinor, fulfilment, sellerQuantity, handlingDays,
                shipFrom, status, productLive, createdAt, updatedAt, version, null, null, Map.of(), null, null);
    }

    public static final int MAX_HANDLING_DAYS = 30;
    public static final int MAX_SKU = 40;
    private static final Pattern ATIN = Pattern.compile("AT[0-9A-Z]{8}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public Offer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sellerKind, "sellerKind");
        Objects.requireNonNull(status, "status");
        conditionNote = conditionNote == null || conditionNote.isBlank() ? null : conditionNote.strip();
        sellerBehaviour = sellerBehaviour == null ? Map.of() : Map.copyOf(sellerBehaviour);
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
                terms.shipFrom(), status, productLive, createdAt, now, version, conditionNote, packaging,
                sellerBehaviour, heldReason, taxBasis);
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

    /**
     * A paused offer comes back. One whose tax basis is not verified does not go live: it waits as TAX_HELD
     * ({@link #HOLD_UNVERIFIED}) until the tax check has run.
     */
    public Offer resume(Instant now) {
        if (status != OfferStatus.PAUSED) {
            throw new IllegalStateException("Only a paused offer can be resumed");
        }
        if (taxBasis == null) {
            return heldFor(HOLD_UNVERIFIED, now);
        }
        return withStatus(OfferStatus.ACTIVE, now);
    }

    public Offer withdraw(Instant now) {
        if (status == OfferStatus.WITHDRAWN) {
            throw new IllegalStateException("The offer is already withdrawn");
        }
        return withStatus(OfferStatus.WITHDRAWN, now);
    }

    /**
     * The offering seller has no RESOLVED tax decision on this ATIN (or tax-returns could not be asked): the offer is not
     * buyable, not in search and not in the buy box until {@link #releasedFromTaxHold}. Only an active offer is held; a
     * paused one stays paused (it is checked when it comes back).
     */
    public Offer heldForTax(String reason, Instant now) {
        if (status != OfferStatus.ACTIVE) {
            throw new IllegalStateException("Only an active offer can be held for tax (was " + status + ")");
        }
        return heldFor(reason, now);
    }

    /**
     * The seller now has a RESOLVED tax decision: the held offer is active. Only the tax release component calls this
     * (checked by {@code TaxGateArchitectureTest}); it never invents a basis.
     */
    public Offer releasedFromTaxHold(String basis, Instant now) {
        if (status != OfferStatus.TAX_HELD) {
            throw new IllegalStateException("Only an offer held for tax can be released (was " + status + ")");
        }
        if (basis == null || basis.isBlank()) {
            throw new IllegalArgumentException("A release needs the tax basis it rests on");
        }
        return new Offer(id, atin, productAtin, productId, gtin, categoryId, sellerOrgId, sellerKind, sellerSku,
                inventorySku, condition, priceMinor, listPriceMinor, currency, shippingMinor, fulfilment,
                sellerQuantity, handlingDays, shipFrom, OfferStatus.ACTIVE, productLive, createdAt, now, version,
                conditionNote, packaging, sellerBehaviour, null, basis);
    }

    /** Records that tax-returns holds a RESOLVED decision (an offer that was live before the check existed). */
    public Offer withTaxBasis(String basis, Instant now) {
        return new Offer(id, atin, productAtin, productId, gtin, categoryId, sellerOrgId, sellerKind, sellerSku,
                inventorySku, condition, priceMinor, listPriceMinor, currency, shippingMinor, fulfilment,
                sellerQuantity, handlingDays, shipFrom, status, productLive, createdAt, now, version, conditionNote,
                packaging, sellerBehaviour, heldReason, basis);
    }

    /**
     * The offer moves to another variant of another page (two duplicate pages were merged). It is a different ATIN now, so
     * the seller's tax decision on the old one says nothing about it: the tax basis is dropped and an active offer waits as
     * TAX_HELD ({@link #HOLD_UNVERIFIED}) until the tax release component has asked tax-returns about the new ATIN.
     */
    public Offer relocatedTo(String newAtin, String newProductAtin, UUID newProductId, UUID newCategoryId,
                             boolean newProductLive, Instant now) {
        if (status == OfferStatus.WITHDRAWN) {
            throw new IllegalStateException("A withdrawn offer cannot be moved");
        }
        boolean wasActive = status == OfferStatus.ACTIVE;
        return new Offer(id, newAtin, newProductAtin, newProductId, gtin, newCategoryId, sellerOrgId, sellerKind, sellerSku,
                inventorySku, condition, priceMinor, listPriceMinor, currency, shippingMinor, fulfilment, sellerQuantity,
                handlingDays, shipFrom, wasActive ? OfferStatus.TAX_HELD : status, newProductLive, createdAt, now, version,
                conditionNote, packaging, sellerBehaviour, wasActive ? HOLD_UNVERIFIED : heldReason, null);
    }

    /** The same offer with a note on its condition and the box it ships in. */
    public Offer withDetails(String newConditionNote, OfferPackage newPackaging, Instant now) {
        return new Offer(id, atin, productAtin, productId, gtin, categoryId, sellerOrgId, sellerKind, sellerSku,
                inventorySku, condition, priceMinor, listPriceMinor, currency, shippingMinor, fulfilment,
                sellerQuantity, handlingDays, shipFrom, status, productLive, createdAt, now, version, newConditionNote,
                newPackaging, sellerBehaviour, heldReason, taxBasis);
    }

    public Offer withProductLive(boolean live, Instant now) {
        return new Offer(id, atin, productAtin, productId, gtin, categoryId, sellerOrgId, sellerKind, sellerSku,
                inventorySku, condition, priceMinor, listPriceMinor, currency, shippingMinor, fulfilment,
                sellerQuantity, handlingDays, shipFrom, status, live, createdAt, now, version, conditionNote, packaging,
                sellerBehaviour, heldReason, taxBasis);
    }

    private Offer withStatus(OfferStatus next, Instant now) {
        return new Offer(id, atin, productAtin, productId, gtin, categoryId, sellerOrgId, sellerKind, sellerSku,
                inventorySku, condition, priceMinor, listPriceMinor, currency, shippingMinor, fulfilment,
                sellerQuantity, handlingDays, shipFrom, next, productLive, createdAt, now, version, conditionNote,
                packaging, sellerBehaviour, null, taxBasis);
    }

    private Offer heldFor(String reason, Instant now) {
        return new Offer(id, atin, productAtin, productId, gtin, categoryId, sellerOrgId, sellerKind, sellerSku,
                inventorySku, condition, priceMinor, listPriceMinor, currency, shippingMinor, fulfilment,
                sellerQuantity, handlingDays, shipFrom, OfferStatus.TAX_HELD, productLive, createdAt, now, version,
                conditionNote, packaging, sellerBehaviour, reason, null);
    }

    /** The seller-editable commercial terms of an offer. */
    public record Terms(OfferCondition condition, long priceMinor, Long listPriceMinor, long shippingMinor,
                        Fulfilment fulfilment, int sellerQuantity, int handlingDays, ShipFrom shipFrom,
                        String inventorySku) {
    }
}
