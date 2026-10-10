package com.afrotransact.retail.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Design data (design/at-inventory-hub.js) as domain objects for unit tests. */
final class Fixtures {

    static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");
    static final LocalDate TODAY = LocalDate.parse("2026-10-10");

    private Fixtures() {
    }

    static Supplier accra(SupplierStatus status) {
        Supplier s = Supplier.onboard(UUID.randomUUID(), "SUP-ACC", "Accra Pack Foods Ltd", "GH", "Accra, Ghana",
                "GHS", "Net 30 · 30% deposit", 35, List.of("FSSC 22000"), List.of(), NOW);
        return status == SupplierStatus.ONBOARDING ? s : s.changeStatus(SupplierStatus.APPROVED, null, NOW)
                .changeStatus(status, "test", NOW);
    }

    static Supplier approvedAccra() {
        return Supplier.onboard(UUID.randomUUID(), "SUP-ACC", "Accra Pack Foods Ltd", "GH", "Accra, Ghana", "GHS",
                "Net 30", 35, List.of(), List.of(), NOW).changeStatus(SupplierStatus.APPROVED, null, NOW);
    }

    static RetailItem item(Supplier supplier, String sku, String name, int shelfDays, StorageType storage,
                           String duty) {
        return new RetailItem(UUID.randomUUID(), sku, 200061, "860012000616", name, "Groceries", null, supplier.id(),
                List.of(new SupplierItem(supplier.id(), "ACC-" + sku, 24, 0, 2000, "GHS")), "GH", "0910.91.0000",
                new BigDecimal(duty), shelfDays, storage, 150, "6 oz", 6, 24, 200, 192, 799, "USD",
                ListingStatus.DRAFT, NOW, NOW, 0);
    }

    static PurchaseOrder po(Supplier supplier, List<PoLine> lines) {
        return PurchaseOrder.draft(UUID.randomUUID(), "PO-1P-323", supplier,
                new FxRate("GHS", "USD", new BigDecimal("15.42"), TODAY), "HOU-FC", "FOB Tema", TODAY,
                TODAY.plusDays(40), lines, ApprovalTier.STANDARD, "buyer", NOW);
    }

    /** Draft → … → Sent. */
    static PurchaseOrder sent(PurchaseOrder po) {
        return po.submit("buyer", NOW).approve("lead", null, NOW).send("buyer", NOW);
    }
}
