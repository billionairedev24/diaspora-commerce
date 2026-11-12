package com.afrotransact.catalog.port;

/**
 * Copies an image from a public URL into AfroTransact through media-service (POST /v1/assets/import): the same
 * quarantine, inspection and moderation as a browser upload, owned by the organisation that uploaded the sheet.
 */
public interface ImageImporter {

    ImportedImage importImage(String url, String ownerOrg, String uploadedBy);

    /**
     * Copies an image for a slot ({@code MAIN}, {@code OTHER}, {@code SWATCH} or {@code LABEL}) with what the row says
     * about the listing, so media applies the rules of that slot and the AI check can compare the picture with the
     * title and brand. An importer that does not know slots copies the image as before.
     */
    default ImportedImage importImage(String url, String ownerOrg, String uploadedBy, String slot, String title,
                                      String brand, String productTypeCode) {
        return importImage(url, ownerOrg, uploadedBy);
    }

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
