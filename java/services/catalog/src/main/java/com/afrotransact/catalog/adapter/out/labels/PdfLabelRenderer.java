package com.afrotransact.catalog.adapter.out.labels;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import com.afrotransact.catalog.domain.barcode.Label;
import com.afrotransact.catalog.domain.barcode.LabelFormat;
import com.afrotransact.catalog.domain.barcode.LabelLayout;
import com.afrotransact.catalog.domain.barcode.LabelLayout.Text;
import com.afrotransact.catalog.domain.barcode.SheetLayout;
import com.afrotransact.catalog.port.LabelRenderer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/**
 * Labels as PDF (Apache PDFBox, standard Helvetica / Courier, vector bars): one page per printed label sized to the
 * label for label printers, or labels tiled on Letter / A4 with light cut lines for office printers.
 */
@Component
class PdfLabelRenderer implements LabelRenderer {

    private static final float PT_PER_MM = 72f / 25.4f;
    private static final double SHEET_MARGIN_MM = 8;
    private static final double SHEET_GAP_MM = 3;

    @Override
    public LabelFormat format() {
        return LabelFormat.PDF;
    }

    @Override
    public String contentType() {
        return "application/pdf";
    }

    @Override
    public boolean supportsBatch() {
        return true;
    }

    @Override
    public byte[] render(List<Label> labels, SheetLayout layout) {
        List<Label> printed = new ArrayList<>();
        for (Label l : labels) {
            for (int i = 0; i < l.copies(); i++) {
                printed.add(l);
            }
        }
        try (PDDocument doc = new PDDocument()) {
            Fonts fonts = new Fonts(new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),
                    new PDType1Font(Standard14Fonts.FontName.COURIER));
            if (layout.mode() == SheetLayout.Mode.SHEET) {
                sheets(doc, printed, layout.paper(), fonts);
            } else {
                for (Label l : printed) {
                    PDPage page = new PDPage(new PDRectangle(pt(l.widthMm()), pt(l.heightMm())));
                    doc.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        draw(cs, l, 0, l.heightMm(), fonts, false);
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Flow layout: left to right, top to bottom, a new page when the next label does not fit. */
    private static void sheets(PDDocument doc, List<Label> labels, SheetLayout.Paper paper, Fonts fonts)
            throws IOException {
        double pageW = paper.widthMm();
        double pageH = paper.heightMm();
        PDPageContentStream cs = null;
        double x = SHEET_MARGIN_MM;
        double y = SHEET_MARGIN_MM;
        double rowHeight = 0;
        try {
            for (Label l : labels) {
                if (cs != null && x + l.widthMm() > pageW - SHEET_MARGIN_MM) {
                    x = SHEET_MARGIN_MM;
                    y += rowHeight + SHEET_GAP_MM;
                    rowHeight = 0;
                }
                if (cs == null || y + l.heightMm() > pageH - SHEET_MARGIN_MM) {
                    if (cs != null) {
                        cs.close();
                    }
                    PDPage page = new PDPage(new PDRectangle(pt(pageW), pt(pageH)));
                    doc.addPage(page);
                    cs = new PDPageContentStream(doc, page);
                    x = SHEET_MARGIN_MM;
                    y = SHEET_MARGIN_MM;
                    rowHeight = 0;
                }
                draw(cs, l, x, pageH - y, fonts, true);
                x += l.widthMm() + SHEET_GAP_MM;
                rowHeight = Math.max(rowHeight, l.heightMm());
            }
        } finally {
            if (cs != null) {
                cs.close();
            }
        }
    }

    /**
     * Draws one label whose top-left corner is at ({@code left}, {@code top}) mm in PDF coordinates (y up).
     */
    private static void draw(PDPageContentStream cs, Label label, double left, double top, Fonts fonts,
                             boolean cutLine) throws IOException {
        LabelLayout l = LabelLayout.of(label);
        if (cutLine) {
            cs.setStrokingColor(0.8f, 0.8f, 0.8f);
            cs.setLineWidth(0.3f);
            cs.addRect(pt(left), pt(top - l.height()), pt(l.width()), pt(l.height()));
            cs.stroke();
        }
        cs.setNonStrokingColor(0f, 0f, 0f);
        for (Text t : l.texts()) {
            text(cs, t, left, top, t.bold() ? fonts.bold() : fonts.regular());
        }
        int[] runs = label.symbol().runs();
        double x = left + l.barsX();
        for (int i = 0; i < runs.length; i++) {
            double w = runs[i] * l.moduleWidth();
            if (i % 2 == 0) {
                cs.addRect(pt(x), pt(top - l.barsY() - l.barHeight()), pt(w), pt(l.barHeight()));
            }
            x += w;
        }
        cs.fill();
        text(cs, l.humanReadable(), left, top, fonts.mono());
    }

    private static void text(PDPageContentStream cs, Text t, double left, double top, PDType1Font font)
            throws IOException {
        String s = winAnsi(font, t.text());
        float size = pt(t.size());
        float x = pt(left + t.x());
        if (t.centered()) {
            x -= font.getStringWidth(s) / 1000f * size / 2;
        }
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, pt(top - t.baseline()));
        cs.showText(s);
        cs.endText();
    }

    /** Standard 14 fonts use WinAnsiEncoding: strip accents, replace anything else unencodable with '?'. */
    static String winAnsi(PDType1Font font, String text) {
        String base = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        StringBuilder sb = new StringBuilder(base.length());
        base.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            try {
                font.encode(ch);
                sb.append(ch);
            } catch (IllegalArgumentException | IOException e) {
                sb.append('?');
            }
        });
        return sb.toString();
    }

    private static float pt(double mm) {
        return (float) (mm * PT_PER_MM);
    }

    private record Fonts(PDType1Font regular, PDType1Font bold, PDType1Font mono) {
    }
}
