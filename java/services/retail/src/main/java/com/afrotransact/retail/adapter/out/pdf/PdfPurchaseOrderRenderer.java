package com.afrotransact.retail.adapter.out.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.afrotransact.retail.domain.Currencies;
import com.afrotransact.retail.domain.PoLine;
import com.afrotransact.retail.domain.PurchaseOrder;
import com.afrotransact.retail.domain.RetailItem;
import com.afrotransact.retail.domain.Supplier;
import com.afrotransact.retail.port.PurchaseOrderPdfRenderer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * US Letter purchase order with PDFBox (same library and version as catalog's labels): buyer, supplier and ship-to
 * blocks, order / delivery dates, Incoterm, payment terms and FX; one row per line (our SKU, supplier SKU, GTIN,
 * description, quantity, unit price, amount) over as many pages as needed; subtotal, total in the PO currency and
 * its base-currency equivalent; standard terms. Standard-14 fonts (WinAnsi): characters they cannot encode print
 * as {@code ?}.
 */
public class PdfPurchaseOrderRenderer implements PurchaseOrderPdfRenderer {

    static final String TERMS = "1. Quote the PO number on every invoice, packing list, carton label and ASN. "
            + "2. Ship only the quantities ordered; more than 2% over the ordered quantity is refused at the dock. "
            + "3. Send the ASN with lot numbers, manufacturing and expiry dates and GS1-128 carton labels (SSCC) "
            + "before departure. 4. Food must arrive with at least 90 days or half of its shelf life remaining, "
            + "whichever is longer. 5. Invoices are matched against this PO and the goods received (three-way "
            + "match, 1% price tolerance) and paid on the payment terms above.";

    private static final float MARGIN = 48;
    private static final float ROW = 14;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final float[] COLUMNS = {MARGIN, 72, 160, 248, 420, 470, 540};

    private final String buyerName;
    private final String buyerAddress;

    /**
     * @param buyerName    legal name printed as the buyer (AfroTransact Retail)
     * @param buyerAddress one-line address of the buyer
     */
    public PdfPurchaseOrderRenderer(String buyerName, String buyerAddress) {
        this.buyerName = buyerName;
        this.buyerAddress = buyerAddress;
    }

    @Override
    public byte[] render(PurchaseOrder po, Supplier supplier, Map<UUID, RetailItem> items) {
        try (PDDocument doc = new PDDocument()) {
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Purchase order " + po.number());
            info.setAuthor(buyerName);
            info.setSubject(supplier.name());
            new Writer(doc, regular, bold, po, supplier, items).write();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render PO " + po.number(), e);
        }
    }

    static String money(long minor, String currency) {
        BigDecimal major = Currencies.toMajor(minor, currency);
        int digits = Currencies.fractionDigits(currency);
        DecimalFormat f = new DecimalFormat(digits == 0 ? "#,##0" : "#,##0." + "0".repeat(digits),
                DecimalFormatSymbols.getInstance(Locale.ROOT));
        return currency + " " + f.format(major);
    }

    /** One page after another; {@code y} is the next baseline. */
    private final class Writer {

        private final PDDocument doc;
        private final PDFont regular;
        private final PDFont bold;
        private final PurchaseOrder po;
        private final Supplier supplier;
        private final Map<UUID, RetailItem> items;
        private PDPageContentStream cs;
        private float y;
        private int pageNo;

        Writer(PDDocument doc, PDFont regular, PDFont bold, PurchaseOrder po, Supplier supplier,
               Map<UUID, RetailItem> items) {
            this.doc = doc;
            this.regular = regular;
            this.bold = bold;
            this.po = po;
            this.supplier = supplier;
            this.items = items;
        }

        void write() throws IOException {
            newPage();
            header();
            tableHeader();
            for (PoLine l : po.lines()) {
                if (y < MARGIN + 3 * ROW) {
                    newPage();
                    tableHeader();
                }
                line(l);
            }
            if (y < MARGIN + 12 * ROW) {
                newPage();
            }
            totals();
            terms();
            cs.close();
        }

        private void newPage() throws IOException {
            if (cs != null) {
                cs.close();
            }
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            pageNo++;
            y = PDRectangle.LETTER.getHeight() - MARGIN;
            text(bold, 9, MARGIN, MARGIN - 20, buyerName + " · Purchase order " + po.number() + " · page " + pageNo);
        }

        private void header() throws IOException {
            text(bold, 18, MARGIN, y, "PURCHASE ORDER");
            text(bold, 12, 400, y, po.number());
            y -= 2 * ROW;
            List<String> buyer = List.of("Buyer", buyerName, buyerAddress);
            List<String> seller = new ArrayList<>(List.of("Supplier", supplier.name() + " (" + supplier.code() + ")",
                    (supplier.city() == null ? "" : supplier.city() + ", ") + supplier.country()));
            supplier.purchaseOrderContact().ifPresent(c -> seller.add("Attn: " + c.name() + " <" + c.email() + ">"));
            List<String> shipTo = List.of("Ship to", "Fulfilment centre " + po.destinationNodeId(),
                    "Incoterm: " + po.incoterm());
            float top = y;
            block(MARGIN, top, buyer);
            block(220, top, seller);
            block(420, top, shipTo);
            y = top - 5 * ROW;
            text(regular, 9, MARGIN, y, "Order date: " + DATE.format(po.orderDate())
                    + (po.expectedDate() == null ? "" : "    Deliver by: " + DATE.format(po.expectedDate()))
                    + "    Currency: " + po.currency()
                    + (po.currency().equals(po.fx().baseCurrency()) ? "" : "    FX: " + po.fx().unitsPerBase()
                    .toPlainString() + " " + po.currency() + " per " + po.fx().baseCurrency()));
            y -= ROW;
            text(regular, 9, MARGIN, y, "Payment terms: " + (supplier.paymentTerms() == null ? "as agreed"
                    : supplier.paymentTerms()) + "    Approved by: " + nz(po.approvedBy()));
            y -= 2 * ROW;
        }

        private void block(float x, float top, List<String> rows) throws IOException {
            float at = top;
            for (int i = 0; i < rows.size(); i++) {
                text(i == 0 ? bold : regular, i == 0 ? 8 : 9, x, at, rows.get(i));
                at -= ROW - 2;
            }
        }

        private void tableHeader() throws IOException {
            String[] titles = {"#", "Our SKU", "Supplier SKU", "Description", "Qty", "Unit price", "Amount"};
            for (int i = 0; i < titles.length; i++) {
                text(bold, 8, COLUMNS[i], y, titles[i]);
            }
            y -= 4;
            cs.moveTo(MARGIN, y);
            cs.lineTo(PDRectangle.LETTER.getWidth() - MARGIN, y);
            cs.stroke();
            y -= ROW - 2;
        }

        private void line(PoLine l) throws IOException {
            RetailItem item = items.get(l.itemId());
            text(regular, 8, COLUMNS[0], y, Integer.toString(l.lineNo()));
            text(regular, 8, COLUMNS[1], y, clip(l.sku(), 16));
            text(regular, 8, COLUMNS[2], y, clip(l.supplierSku(), 16));
            text(regular, 8, COLUMNS[3], y, clip(l.description(), 34));
            right(regular, 8, COLUMNS[5] - 10, y, Integer.toString(l.qtyOrdered()));
            right(regular, 8, COLUMNS[6] - 10, y, money(l.unitCostMinor(), po.currency()));
            right(regular, 8, PDRectangle.LETTER.getWidth() - MARGIN, y, money(l.amountMinor(), po.currency()));
            y -= ROW - 4;
            if (item != null) {
                text(regular, 7, COLUMNS[3], y, "GTIN " + item.gtin() + (item.packSize() == null ? ""
                        : " · " + item.packSize()) + " · case of " + item.casePack());
            }
            y -= ROW;
        }

        private void totals() throws IOException {
            float right = PDRectangle.LETTER.getWidth() - MARGIN;
            cs.moveTo(380, y + ROW - 4);
            cs.lineTo(right, y + ROW - 4);
            cs.stroke();
            int units = po.lines().stream().mapToInt(PoLine::qtyOrdered).sum();
            text(regular, 9, 380, y, "Lines / units");
            right(regular, 9, right, y, po.lines().size() + " / " + units);
            y -= ROW;
            text(regular, 9, 380, y, "Subtotal");
            right(regular, 9, right, y, money(po.totalMinor(), po.currency()));
            y -= ROW;
            text(bold, 10, 380, y, "Total");
            right(bold, 10, right, y, money(po.totalMinor(), po.currency()));
            y -= ROW;
            if (!po.currency().equals(po.fx().baseCurrency())) {
                text(regular, 8, 380, y, "Base currency equivalent");
                right(regular, 8, right, y, money(po.totalBaseMinor(), po.fx().baseCurrency()));
                y -= ROW;
            }
            y -= ROW;
        }

        private void terms() throws IOException {
            text(bold, 9, MARGIN, y, "Terms");
            y -= ROW;
            for (String row : wrap(TERMS, 110)) {
                if (y < MARGIN) {
                    newPage();
                }
                text(regular, 8, MARGIN, y, row);
                y -= ROW - 3;
            }
        }

        private void text(PDFont font, float size, float x, float at, String value) throws IOException {
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, at);
            cs.showText(safe(font, value));
            cs.endText();
        }

        private void right(PDFont font, float size, float x, float at, String value) throws IOException {
            String s = safe(font, value);
            text(font, size, x - font.getStringWidth(s) / 1000 * size, at, s);
        }
    }

    /** Replaces characters the standard-14 font cannot encode (WinAnsi) with {@code ?}. */
    static String safe(PDFont font, String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            String c = new String(Character.toChars(cp));
            try {
                font.encode(c);
                out.append(c);
            } catch (IOException | IllegalArgumentException e) {
                out.append('?');
            }
        });
        return out.toString();
    }

    static List<String> wrap(String text, int width) {
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String word : text.split(" ")) {
            if (row.length() + word.length() + 1 > width && !row.isEmpty()) {
                rows.add(row.toString());
                row.setLength(0);
            }
            if (!row.isEmpty()) {
                row.append(' ');
            }
            row.append(word);
        }
        if (!row.isEmpty()) {
            rows.add(row.toString());
        }
        return rows;
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
