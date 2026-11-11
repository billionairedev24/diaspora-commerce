package com.afrotransact.seller.domain;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Checks the documents of an application against what its rule asks for. */
public final class SellingDocuments {

    private SellingDocuments() {
    }

    /**
     * Throws {@link SellerRuleViolation} (field errors by {@code documents.<KIND>}) when a required document is
     * missing, an invoice is older than 180 days, a permit or policy has no future expiry date, an insurance policy
     * is not confirmed to name AfroTransact, or a document of a kind the rule does not ask for is sent.
     */
    public static void require(List<SellingDocumentRequirement> requirements, List<SellingDocument> docs,
                               LocalDate today) {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<SellingDocumentKind> asked = EnumSet.noneOf(SellingDocumentKind.class);
        requirements.forEach(r -> asked.add(r.kind()));
        for (SellingDocumentRequirement r : requirements) {
            boolean present = docs.stream().anyMatch(d -> d.kind() == r.kind());
            if (!present && !r.optional()) {
                errors.put("documents." + r.kind(), "Upload: " + r.kind().label());
            }
        }
        for (SellingDocument d : docs) {
            String key = "documents." + d.kind();
            if (!asked.contains(d.kind())) {
                errors.put(key, "This document is not needed for this approval");
                continue;
            }
            if (d.assetId() == null || d.assetId().isBlank()) {
                errors.put(key, "Upload the file");
                continue;
            }
            check(d, today).ifPresent(m -> errors.put(key, m));
        }
        if (!errors.isEmpty()) {
            throw new SellerRuleViolation("seller.selling-documents-invalid",
                    "Some documents are missing or need fixing", errors);
        }
    }

    private static Optional<String> check(SellingDocument d, LocalDate today) {
        SellingDocumentKind k = d.kind();
        if (k.dated()) {
            if (d.issuedOn() == null) {
                return Optional.of("Enter the date on the document");
            }
            if (d.issuedOn().isAfter(today)) {
                return Optional.of("The date is in the future");
            }
            if (d.issuedOn().isBefore(today.minusDays(k.maxAgeDays()))) {
                return Optional.of("Must be dated within the last " + k.maxAgeDays() + " days");
            }
        }
        if (k.expiryRequired() && d.expiresOn() == null) {
            return Optional.of("Enter the expiry date");
        }
        if (d.expiresOn() != null && !d.expiresOn().isAfter(today)) {
            return Optional.of("This document has already expired");
        }
        if (k.namesAfroTransact() && !d.namesAfroTransact()) {
            return Optional.of("The policy must name AfroTransact as additional insured");
        }
        return Optional.empty();
    }
}
