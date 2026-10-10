package com.afrotransact.deliveryruns.port;

import java.util.Optional;
import java.util.UUID;

/**
 * media-service (local port 8207): {@code GET /v1/assets/{id}} with the client-credentials token of
 * {@code delivery-runs-service} (scope {@code media:read}). Throws {@link UpstreamUnavailableException} when media
 * cannot answer.
 */
public interface MediaGateway {

    /** The asset, or empty when media knows no such asset. */
    Optional<MediaAsset> asset(UUID id);

    /**
     * Asset metadata.
     *
     * @param id          asset id
     * @param url         public URL
     * @param width       measured width (px; 0 when unknown)
     * @param height      measured height (px; 0 when unknown)
     * @param status      PENDING, READY, FLAGGED or REJECTED
     * @param contentType detected content type
     */
    record MediaAsset(UUID id, String url, int width, int height, String status, String contentType) {

        public boolean ready() {
            return "READY".equalsIgnoreCase(status);
        }

        public boolean image() {
            return contentType == null || contentType.startsWith("image/");
        }
    }
}
