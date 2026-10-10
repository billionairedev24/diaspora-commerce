package com.afrotransact.retail.adapter.out.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.retail.domain.ApprovalTier;
import com.afrotransact.retail.domain.FxRate;
import com.afrotransact.retail.domain.ListingStatus;
import com.afrotransact.retail.domain.PoLine;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.StorageType;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.domain.SupplierContact;
import com.afrotransact.retail.domain.SupplierItem;
import com.afrotransact.retail.domain.SupplierStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PdfPurchaseOrderRendererTest {

    private static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-10");

    private final PdfPurchaseOrderRenderer renderer = new PdfPurchaseOrderRenderer("AfroTransact Retail",
            "Houston, TX, USA");

    @Test
    void rendersLinesTermsAndTotals() throws IOException {
        Supplier supplier = supplier("Accra Pack Foods Ltd");
        RetailItem shito = item(supplier, "AT-SHI-200062", "860012000623");
        PurchaseOrder po = po(supplier, List.of(
                PoLine.of(1, shito.id(), "AT-SHI-200062", "Shito Black Pepper Sauce, Hot, 12 oz", "ACC-SHI-12", 480,
                        3470),
                PoLine.of(2, UUID.randomUUID(), "AT-JOL-200061", "Jollof Seasoning, 6 oz", "ACC-JOL-6", 1200, 2005)));

        String text = text(renderer.render(po, supplier, Map.of(shito.id(), shito)));

        assertThat(text).contains("PURCHASE ORDER", "PO-1P-777", "Accra Pack Foods Ltd (SUP-ACC)",
                "Attn: Efua Mensah <efua@accrapack.example>", "Fulfilment centre HOU-FC", "Incoterm: FOB Tema",
                "Deliver by: 19 Nov 2026", "Payment terms: Net 30");
        assertThat(text).contains("AT-SHI-200062", "ACC-SHI-12", "Shito Black Pepper Sauce", "GHS 34.70",
                "GHS 16,656.00", "GTIN 860012000623");
        assertThat(text).contains("Jollof Seasoning, 6 oz", "GHS 24,060.00");
        // 16 656.00 + 24 060.00, and the USD equivalent at 15.42 GHS per USD
        assertThat(text).contains("Lines / units", "2 / 1680", "Subtotal", "Total", "GHS 40,716.00",
                "USD 2,640.47");
        assertThat(text).contains("Terms", "three-way", "Quote the PO number");
    }

    @Test
    void longOrdersFlowOverSeveralPagesAndUnencodableCharactersDoNotBreakIt() throws IOException {
        Supplier supplier = supplier("Ɔbaa Foods Ɛnterprise");
        List<PoLine> lines = new ArrayList<>();
        for (int i = 1; i <= 70; i++) {
            lines.add(PoLine.of(i, UUID.randomUUID(), "AT-X-" + i, "Item " + i, "S-" + i, 10, 100));
        }
        byte[] pdf = renderer.render(po(supplier, lines), supplier, Map.of());
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            assertThat(doc.getDocumentInformation().getTitle()).isEqualTo("Purchase order PO-1P-777");
        }
        String text = text(pdf);
        assertThat(text).contains("Item 70", "?baa Foods ?nterprise", "page 2");
    }

    @Test
    void formatsMoneyPerCurrencyDigits() {
        assertThat(PdfPurchaseOrderRenderer.money(123_456, "USD")).isEqualTo("USD 1,234.56");
        assertThat(PdfPurchaseOrderRenderer.money(5_000, "XOF")).isEqualTo("XOF 5,000");
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static Supplier supplier(String name) {
        return Supplier.onboard(UUID.randomUUID(), "SUP-ACC", name, "GH", "Accra", "GHS", "Net 30", 35, List.of(),
                List.of(new SupplierContact("Kofi Boateng", "Quality", "kofi@accrapack.example", null),
                        new SupplierContact("Efua Mensah", "Sales", "efua@accrapack.example", null)), NOW)
                .changeStatus(SupplierStatus.APPROVED, null, NOW);
    }

    private static PurchaseOrder po(Supplier supplier, List<PoLine> lines) {
        return PurchaseOrder.draft(UUID.randomUUID(), "PO-1P-777", supplier,
                new FxRate("GHS", "USD", new BigDecimal("15.42"), TODAY), "HOU-FC", "FOB Tema", TODAY,
                TODAY.plusDays(40), lines, ApprovalTier.STANDARD, "buyer", NOW)
                .submit("buyer", NOW).approve("lead", null, NOW);
    }

    private static RetailItem item(Supplier supplier, String sku, String gtin) {
        return new RetailItem(UUID.randomUUID(), sku, 200062, gtin, "Shito", "Groceries", null, supplier.id(),
                List.of(new SupplierItem(supplier.id(), "ACC-SHI-12", 24, 0, 3470, "GHS")), "GH", "2103.90.0000",
                new BigDecimal("6.4"), 365, StorageType.AMBIENT, 150, "12 oz jar", 6, 24, 400, 192, 899, "USD",
                ListingStatus.DRAFT, NOW, NOW, 0);
    }
}
