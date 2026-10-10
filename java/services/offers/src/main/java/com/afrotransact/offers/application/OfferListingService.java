package com.afrotransact.offers.application;

import java.util.List;
import java.util.UUID;

/** Anonymous storefront reads: buy box, "other sellers on this product", single offers for the cart. */
public interface OfferListingService {

    int MAX_BATCH = 50;

    /** Buy box winner with reasons plus the other listed offers of a variant ATIN, sorted by landed price. */
    BuyBoxView offers(String atin, String currency);

    /** Buy boxes of many variant ATINs (search cards, variant pickers, carts). */
    List<BuyBoxView> buyBoxes(List<String> atins, String currency);

    /**
     * One card summary per product page (search cards): the best variant winner (lowest landed price), the reasons it
     * won, offer count and lowest prices across every variant. {@code atin} of each view is the product ATIN and
     * {@code otherOffers} is empty.
     */
    List<BuyBoxView> productSummaries(List<String> productAtins, String currency);

    /** Buy boxes of every variant of a product page that has offers. */
    List<BuyBoxView> productBuyBoxes(String productAtin, String currency);

    /** One offer with its buy box rank and whether it can be bought now (add a non-buy-box offer to the cart). */
    ListedOffer offer(UUID offerId);
}
