package com.afrotransact.inventory.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.afrotransact.inventory.domain.PostalAddress;

/** carrier-gateway (S257): inbound labels from the seller to the FC, and tracking of numbers bought elsewhere. */
public interface CarrierGateway {

    /**
     * Buys a label ({@code POST /v1/labels}, fulfilment FBA). Retries with the same key return the same label.
     *
     * @throws CarrierException retryable (503) or rejected (422)
     */
    PurchasedLabel buyLabel(LabelPurchase purchase, String idempotencyKey);

    /**
     * Starts tracking a number the seller bought elsewhere ({@code POST /v1/tracking}).
     *
     * @throws CarrierException when the gateway cannot be reached or rejects the number
     */
    void registerTracking(String carrier, String trackingNumber, String reference);

    /** What to buy: carrier and service from a quote, addresses and one parcel per box. */
    record LabelPurchase(String carrier, String service, String reference, String originNode, PostalAddress from,
                         PostalAddress to, List<Parcel> parcels, LocalDate shipDate) {

        public LabelPurchase {
            parcels = List.copyOf(parcels);
        }
    }

    /** One box. */
    record Parcel(BigDecimal weightLb, BigDecimal lengthIn, BigDecimal widthIn, BigDecimal heightIn) {
    }

    /** The bought label. */
    record PurchasedLabel(String labelId, String carrier, String service, String trackingNumber, long costMinor,
                          String currency, String documentUrl) {
    }

    /** Gateway unavailable (retryable) or label refused. */
    class CarrierException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final boolean retryable;
        private final String code;

        public CarrierException(String code, String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }

        public String code() {
            return code;
        }
    }
}
