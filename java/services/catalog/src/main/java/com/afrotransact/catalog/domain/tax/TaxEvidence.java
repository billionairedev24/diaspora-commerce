package com.afrotransact.catalog.domain.tax;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A file that proves an exemption fact (label photo, Drug Facts, Supplement Facts), stored with the variant. A new
 * upload of the same fact is a new {@code version}; earlier files are kept for audit.
 */
public record TaxEvidence(UUID id, String variantAtin, String ownerOrg, String factId, int version, String fileName,
                          String contentType, long bytes, String uploadedBy, Instant uploadedAt) {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    public static final Set<String> TYPES = Set.of("application/pdf", "image/jpeg", "image/png");

    /** Why a file is not acceptable as evidence, in plain words; null when it is. */
    public static String problem(String contentType, long bytes) {
        if (!TYPES.contains(contentType)) {
            return "Use a PDF, JPEG or PNG file";
        }
        if (bytes <= 0) {
            return "The file is empty";
        }
        if (bytes > MAX_BYTES) {
            return "The file is larger than 10 MB";
        }
        return null;
    }
}
