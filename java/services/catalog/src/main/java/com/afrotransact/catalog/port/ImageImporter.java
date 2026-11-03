package com.afrotransact.catalog.port;

/**
 * Copies an image from a public URL into AfroTransact through media-service (POST /v1/assets/import): the same
 * quarantine, inspection and moderation as a browser upload, owned by the organisation that uploaded the sheet.
 */
public interface ImageImporter {

    ImportedImage importImage(String url, String ownerOrg, String uploadedBy);

    /**
     * The result: {@code status} READY (published), FLAGGED (held for review), or REJECTED with a reason.
     */
    record ImportedImage(String url, int width, int height, long bytes, String contentType, String status,
                         String reason) {

        public boolean ready() {
            return "READY".equals(status) && url != null && !url.isBlank();
        }
    }

    /** media-service could not be reached or failed; nothing is known about the image. */
    class ImageServiceUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ImageServiceUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
