package com.afrotransact.retail.domain;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** One packing-list line of an advance ship notice: units shipped against a PO line with the supplier's batch. */
public record AsnLine(int lineNo, int poLineNo, UUID itemId, String sku, int qty, String lotNo, LocalDate mfgDate,
                      LocalDate expDate) {

    public AsnLine {
        Objects.requireNonNull(itemId, "itemId");
        if (qty < 1) {
            throw new RetailRuleViolation("retail.asn-invalid", "ASN line " + lineNo + ": quantity must be positive");
        }
        lotNo = lotNo == null || lotNo.isBlank() ? null : lotNo.trim().toUpperCase(Locale.ROOT);
        if (lotNo != null && !LotRules.validLot(lotNo)) {
            throw new RetailRuleViolation("retail.asn-invalid", "ASN line " + lineNo
                    + ": lot must be 4–20 letters, digits or dashes");
        }
        if (mfgDate != null && expDate != null && !expDate.isAfter(mfgDate)) {
            throw new RetailRuleViolation("retail.asn-invalid", "ASN line " + lineNo
                    + ": expiry must be after manufacture date");
        }
    }
}
