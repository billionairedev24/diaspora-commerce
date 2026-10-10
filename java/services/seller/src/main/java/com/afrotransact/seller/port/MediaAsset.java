package com.afrotransact.seller.port;

/** An uploaded document fetched from media-service. */
public record MediaAsset(String id, String fileName, String contentType, byte[] bytes) {
}
