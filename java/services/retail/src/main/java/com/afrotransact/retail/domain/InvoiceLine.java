package com.afrotransact.retail.domain;

/** One supplier invoice line against a PO line, in the invoice (PO) currency. */
public record InvoiceLine(int poLineNo, int qty, long unitPriceMinor) {

    public InvoiceLine {
        if (qty < 1 || unitPriceMinor < 0) {
            throw new RetailRuleViolation("retail.invoice-invalid", "Invoice line for PO line " + poLineNo
                    + " needs a positive quantity and a non-negative price");
        }
    }

    public long amountMinor() {
        return Math.multiplyExact((long) qty, unitPriceMinor);
    }
}
