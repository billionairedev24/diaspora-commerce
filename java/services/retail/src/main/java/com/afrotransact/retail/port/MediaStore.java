package com.afrotransact.retail.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * media-service for documents exchanged with suppliers (S225): the PO PDF and shipment / ASN documents. Files are
 * kept as media asset ids; retail never stores file contents.
 */
public interface MediaStore {

    /** Media purpose of purchase-order PDFs (private, retail organisation). */
    String PURPOSE_PURCHASE_ORDER = "purchase-order";

    /**
     * Uploads a file (request upload, PUT the bytes to storage, complete) and returns the finalised asset.
     *
     * @throws MediaUnavailableException when media or the storage cannot be reached, or rejects the file
     */
    MediaAsset store(String purpose, String fileName, String contentType, byte[] content);

    /** The asset's metadata, empty when media does not know it. */
    Optional<MediaAsset> find(UUID assetId);

    /** A signed download URL valid for {@code ttl} (media caps it per purpose). */
    SignedLink signedLink(UUID assetId, Duration ttl);

    /**
     * A media asset.
     *
     * @param status PENDING (upload not completed), READY, FLAGGED or REJECTED
     */
    record MediaAsset(UUID id, String purpose, String fileName, String contentType, long bytes, String status) {

        public boolean usable() {
            return "READY".equals(status) || "FLAGGED".equals(status);
        }
    }

    /** A time-limited download link. */
    record SignedLink(String url, Instant expiresAt) {
    }
}
