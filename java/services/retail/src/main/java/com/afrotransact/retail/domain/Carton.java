package com.afrotransact.retail.domain;

/** A case on the ASN identified by its GS1-128 SSCC (AI 00), holding units of one ASN line. */
public record Carton(String sscc, int asnLineNo, int qty) {

    public Carton {
        if (sscc == null || !sscc.matches("\\d{18}") || Gtin.checkDigit(sscc.substring(0, 17)) != sscc.charAt(17) - '0') {
            throw new RetailRuleViolation("retail.asn-invalid", "SSCC must be 18 digits with a valid check digit");
        }
        if (qty < 1) {
            throw new RetailRuleViolation("retail.asn-invalid", "Carton " + sscc + " must hold at least one unit");
        }
    }
}
