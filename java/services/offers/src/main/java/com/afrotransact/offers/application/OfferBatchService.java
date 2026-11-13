package com.afrotransact.offers.application;

import java.util.List;
import java.util.UUID;

import com.afrotransact.offers.domain.Fulfilment;
import com.afrotransact.offers.domain.OfferCondition;
import com.afrotransact.offers.domain.OfferPackage;
import com.afrotransact.offers.domain.OfferStatus;
import com.afrotransact.offers.domain.ShipFrom;

/**
 * Many offers in one call for bulk uploads (S393, S394). Every row is its own transaction and has its own result, so a
 * good row is never held back by a bad one. A batch can only touch the offers of one organisation: a seller's own, or the
 * organisation a staff or service caller names (catalog-service sends the uploading seller's). The same checks as a single
 * offer apply: this service stores an offer ACTIVE only through {@link OfferService#create} (the tax rule, S384), and never
 * changes an offer's status.
 */
public interface OfferBatchService {

    /** Rows per call (larger batches are refused with 422). */
    int MAX_ROWS = 500;

    /**
     * Creates each offer, or changes the terms of the seller's open offer on the same ATIN, condition and channel
     * (fulfilled by AfroTransact or by the seller). Sending the same rows again changes nothing (UNCHANGED).
     */
    List<RowResult> upsert(UpsertBatch batch);

    /** Changes price, list price and stock of the seller's open offers by seller SKU or ATIN; null fields are left as they are. */
    List<RowResult> setPricesAndStock(PriceStockBatch batch);

    /**
     * @param sellerOrgId staff and service callers only; a seller always acts for its own organisation (another one is 403)
     * @param importId    the upload these rows come from, kept in the audit trail
     * @param uploadedBy  who uploaded the file, kept in the audit trail
     */
    record UpsertBatch(String sellerOrgId, UUID importId, String uploadedBy, List<UpsertRow> rows) {

        public UpsertBatch {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /**
     * One offer. Content of the page is not here: it is never taken from a file (S383).
     *
     * @param rowKey the caller's own reference for the row, echoed in the result
     */
    record UpsertRow(String rowKey, String atin, String sellerSku, OfferCondition condition, long priceMinor,
                     Long listPriceMinor, Long shippingMinor, Fulfilment fulfilment, Integer quantity,
                     Integer handlingDays, ShipFrom shipFrom, String conditionNote, OfferPackage packaging) {
    }

    /** As {@link UpsertBatch}. */
    record PriceStockBatch(String sellerOrgId, UUID importId, String uploadedBy, List<PriceStockRow> rows) {

        public PriceStockBatch {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /**
     * Price and stock of the offers with this seller SKU, or on this ATIN when no SKU is given, or (with neither) the one
     * listing of the seller that carries this barcode: a barcode that matches none or more than one of the seller's listings
     * fails the row.
     *
     * @param listPriceMinor 0 clears the list price; null leaves it
     * @param quantity       seller-fulfilled stock only; null leaves it
     */
    record PriceStockRow(String rowKey, String sellerSku, String atin, Long priceMinor, Long listPriceMinor,
                         Integer quantity, String gtin) {

        /** A row named by SKU or ATIN. */
        public PriceStockRow(String rowKey, String sellerSku, String atin, Long priceMinor, Long listPriceMinor,
                             Integer quantity) {
            this(rowKey, sellerSku, atin, priceMinor, listPriceMinor, quantity, null);
        }
    }

    enum Outcome {
        CREATED, UPDATED, UNCHANGED,
        /** The offer exists but waits for the seller's tax decision: it is not for sale yet. */
        HELD,
        /** Nothing was stored for this row; {@code code} and {@code message} say why. */
        FAILED
    }

    /**
     * @param status  the offer's status after the row; null for FAILED
     * @param offerId the offer; null for FAILED. A SKU shared by two channels reports the first offer.
     * @param code    FAILED and HELD: the stable code ({@code offers.selling-approval-required}, {@code TAX_DECISION_MISSING})
     * @param message plain words for the seller
     */
    record RowResult(String rowKey, Outcome outcome, UUID offerId, String atin, OfferStatus status, String code,
                     String message) {
    }
}
