package com.afrotransact.inventory.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * catalog-service labels (S032, S257): the stock owner's AT-FN unit label code per variant ATIN
 * ({@code GET /v1/labels/units/{atin}}) and batch rendering of unit and carton labels ({@code POST /v1/labels/batch}).
 */
public interface UnitLabelClient {

    /**
     * The owner's AT-FN code for a variant (issued once per owner and variant by catalog); empty when catalog labels
     * this unit with the manufacturer barcode (AfroTransact Retail stock with a GTIN).
     *
     * @throws LabelServiceException when catalog cannot be reached or refuses the variant
     */
    Optional<String> unitLabelCode(String atin, String ownerOrg);

    /**
     * Renders labels as one PDF or ZPL document.
     *
     * @throws LabelServiceException when catalog cannot be reached or rejects the labels
     */
    RenderedLabels render(List<LabelSpec> labels, String format);

    /**
     * One label (catalog LabelRequest): {@code kind} UNIT (Code 128 of {@code data}) or CUSTOM (cartons).
     *
     * @param lines up to 4 text lines under the title
     */
    record LabelSpec(String kind, String data, String caption, String title, List<String> lines, int copies,
                     LocalDate expiry) {

        public LabelSpec {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    /** A rendered document. */
    record RenderedLabels(String contentType, String fileName, byte[] content) {
    }

    /** catalog unavailable (retryable, 503) or label data rejected (422). */
    class LabelServiceException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final boolean retryable;

        public LabelServiceException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
