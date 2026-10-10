package com.afrotransact.retail.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * EDI trading-partner settings of a supplier (S225). Enabled suppliers receive the X12 850 of every sent PO.
 *
 * @param interchangeId the supplier's ISA receiver id (1–15 characters)
 * @param endpoint      {@code sftp://host[:port]/inbound/dir} for SFTP, the partner's {@code https://} AS2 URL for AS2
 */
public record SupplierEdiProfile(UUID supplierId, boolean enabled, EdiProtocol protocol, String interchangeId,
                                 String endpoint, String updatedBy, Instant updatedAt, long version) {

    public SupplierEdiProfile {
        Objects.requireNonNull(supplierId, "supplierId");
        if (protocol == null) {
            throw new RetailRuleViolation("retail.edi-invalid", "Choose the EDI protocol (SFTP or AS2)");
        }
        interchangeId = interchangeId == null ? null : interchangeId.trim().toUpperCase(Locale.ROOT);
        if (interchangeId == null || !interchangeId.matches("[A-Z0-9 ]{1,15}")) {
            throw new RetailRuleViolation("retail.edi-invalid",
                    "Interchange id is the supplier's ISA receiver id: 1–15 letters or digits");
        }
        endpoint = endpoint == null ? null : endpoint.trim();
        String scheme = protocol == EdiProtocol.SFTP ? "sftp://" : "https://";
        if (endpoint == null || !endpoint.startsWith(scheme) || endpoint.length() <= scheme.length()) {
            throw new RetailRuleViolation("retail.edi-invalid", protocol + " endpoint must start with " + scheme);
        }
    }
}
