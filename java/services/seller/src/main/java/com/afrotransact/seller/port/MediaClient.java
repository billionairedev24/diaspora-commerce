package com.afrotransact.seller.port;

/** Reads uploaded documents from media-service ({@code GET /v1/assets/{id}} then the signed URL). */
public interface MediaClient {

    MediaAsset fetch(String assetId);

    /** A short-lived URL a reviewer can open (signed URL from media-service). */
    String viewUrl(String assetId);
}
