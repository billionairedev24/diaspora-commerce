package com.afrotransact.retail.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One PO line in the supplier's currency. Received (good + damaged) and invoiced quantities accumulate from goods
 * receipts and matched supplier invoices.
 */
public record PoLine(int lineNo, UUID itemId, String sku, String description, String supplierSku, int qtyOrdered,
                     long unitCostMinor, int qtyReceived, int qtyDamaged, int qtyInvoiced) {

    public PoLine {
        Objects.requireNonNull(itemId, "itemId");
        if (lineNo < 1) {
            throw new IllegalArgumentException("lineNo starts at 1");
        }
        if (qtyOrdered < 1) {
            throw new RetailRuleViolation("retail.po-line-invalid", "Line " + lineNo + ": quantity must be positive");
        }
        if (unitCostMinor < 1) {
            throw new RetailRuleViolation("retail.po-line-invalid", "Line " + lineNo + ": unit price must be positive");
        }
        if (qtyReceived < 0 || qtyDamaged < 0 || qtyDamaged > qtyReceived || qtyInvoiced < 0) {
            throw new IllegalArgumentException("Inconsistent received/invoiced quantities on line " + lineNo);
        }
    }

    public static PoLine of(int lineNo, UUID itemId, String sku, String description, String supplierSku, int qty,
                            long unitCostMinor) {
        return new PoLine(lineNo, itemId, sku, description, supplierSku, qty, unitCostMinor, 0, 0, 0);
    }

    /** Ordered quantity × unit price in PO currency minor units. */
    public long amountMinor() {
        return Math.multiplyExact((long) qtyOrdered, unitCostMinor);
    }

    public int openQty() {
        return Math.max(0, qtyOrdered - qtyReceived);
    }

    PoLine receive(int good, int damaged) {
        return new PoLine(lineNo, itemId, sku, description, supplierSku, qtyOrdered, unitCostMinor,
                qtyReceived + good + damaged, qtyDamaged + damaged, qtyInvoiced);
    }

    PoLine invoice(int qty) {
        return new PoLine(lineNo, itemId, sku, description, supplierSku, qtyOrdered, unitCostMinor, qtyReceived,
                qtyDamaged, qtyInvoiced + qty);
    }
}
