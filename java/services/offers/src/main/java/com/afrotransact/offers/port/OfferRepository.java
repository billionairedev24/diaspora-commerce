package com.afrotransact.offers.port;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.offers.domain.Offer;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferStatus;

/** Offers store. "Open" means not withdrawn. */
public interface OfferRepository {

    Offer insert(Offer offer);

    /** Optimistic update on {@code version}; throws ConflictException when the row changed. */
    Offer update(Offer offer);

    Optional<Offer> findById(UUID id);

    /** Open offers for these variant ATINs (every seller, every currency). */
    List<Offer> findOpenByAtins(Collection<String> atins);

    /** Open offers on every variant of a product page. */
    List<Offer> findOpenByProductAtin(String productAtin);

    /** Open offers on every variant of these product pages. */
    List<Offer> findOpenByProductAtins(Collection<String> productAtins);

    Optional<Offer> findOpen(String sellerOrgId, String atin, OfferCondition condition);

    boolean sellerSkuTaken(String sellerOrgId, String sellerSku);

    /** Open offers of a seller carrying this seller SKU (any fulfilment). */
    List<Offer> findOpenBySellerSku(String sellerOrgId, String sellerSku);

    /** Open Fulfilled-by-AfroTransact offers of a seller whose stock is the given inventory SKU. */
    List<Offer> findOpenByInventorySku(String sellerOrgId, String inventorySku);

    /** Distinct ATINs of a seller's open offers. */
    List<String> openAtinsOfSeller(String sellerOrgId);

    OfferPage search(OfferQuery query);

    /** Filter for seller center / staff lists; null fields are not filtered. */
    record OfferQuery(String sellerOrgId, String atin, OfferStatus status, int page, int size) {
    }

    /** A page of offers. */
    record OfferPage(List<Offer> items, long total, int page, int size) {

        public OfferPage {
            items = List.copyOf(items);
        }
    }
}
