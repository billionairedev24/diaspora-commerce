package com.afrotransact.catalog.adapter.out.labels;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import com.afrotransact.catalog.domain.barcode.DefaultBarcodeEncoder;
import com.afrotransact.catalog.domain.barcode.DefaultLabelPolicy;
import com.afrotransact.catalog.domain.barcode.Label;
import com.afrotransact.catalog.domain.barcode.LabelDraft;
import com.afrotransact.catalog.domain.barcode.LabelKind;
import com.afrotransact.catalog.domain.barcode.LabelPolicy;
import com.afrotransact.catalog.domain.barcode.SheetLayout;
import com.afrotransact.catalog.domain.barcode.Symbology;
import com.afrotransact.catalog.testsupport.BarcodeScanner;
import com.google.zxing.BarcodeFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/** S032: SVG, PNG, ZPL and PDF output of the same labels. */
class LabelRenderersTest {

    private final LabelPolicy policy = new DefaultLabelPolicy(new DefaultBarcodeEncoder());

    private Label caseLabel() {
        return policy.build(new LabelDraft(LabelKind.CASE, null, null, "9501101530003", null, "L2610_07",
                LocalDate.of(2026, 10, 1), null, LocalDate.of(2027, 3, 31), null, null, null,
                "Ijebu gari 12 × 2 lb", List.of(), 2));
    }

    private Label unitLabel() {
        return policy.build(new LabelDraft(LabelKind.UNIT, null, "ATF3XK8P2QL", null, null, null, null, null, null,
                null, null, null, "Ankara Wrap Dress", List.of("AT0K7Q2M9H · M / Gold"), null));
    }

    @Test
    void zplMatchesTheSnapshot() throws IOException {
        assertThat(ZplLabelRenderer.zpl(caseLabel())).isEqualTo(resource("labels/case-label.zpl"));
        assertThat(ZplLabelRenderer.zpl(unitLabel())).isEqualTo(resource("labels/unit-label.zpl"));
    }

    @Test
    void zplCode128FieldReproducesOurCodewordsWithInvocationCodes() {
        DefaultBarcodeEncoder encoder = new DefaultBarcodeEncoder();
        assertThat(ZplLabelRenderer.code128Data(caseLabel().symbol().codewords()))
                .isEqualTo(">;>8010950110153000311261001172703311" + "0>6L2610_5F07");
        assertThat(ZplLabelRenderer.code128Data(encoder.code128("A>B^C~D").codewords()))
                .isEqualTo(">:A><B_5EC_7ED");
        assertThat(ZplLabelRenderer.code128Data(encoder.code128("ATF123456").codewords()))
                .isEqualTo(">:ATF>5123456");
        String batch = new String(new ZplLabelRenderer().render(List.of(caseLabel(), unitLabel()),
                SheetLayout.LABEL_PAGES), StandardCharsets.UTF_8);
        assertThat(batch.split("\\^XA", -1)).hasSize(3);
        assertThat(batch).contains("^PQ2\n", "^PQ1\n");
    }

    @Test
    void pngScans() {
        byte[] gs1 = new PngLabelRenderer().render(List.of(caseLabel()), SheetLayout.LABEL_PAGES);
        assertThat(BarcodeScanner.scanPng(gs1, BarcodeFormat.CODE_128).getText())
                .isEqualTo("]C1010950110153000311261001172703311" + "0L2610_07");
        byte[] unit = new PngLabelRenderer().render(List.of(unitLabel()), SheetLayout.LABEL_PAGES);
        assertThat(BarcodeScanner.scanPng(unit, BarcodeFormat.CODE_128).getText()).isEqualTo("ATF3XK8P2QL");
        Label ean = policy.build(new LabelDraft(LabelKind.RETAIL, null, null, "036000291452", null, null, null, null,
                null, null, null, null, "Peak milk", List.of(), null));
        assertThat(BarcodeScanner.scanPng(new PngLabelRenderer().render(List.of(ean), SheetLayout.LABEL_PAGES),
                BarcodeFormat.EAN_13).getText()).isEqualTo("0036000291452");
    }

    @Test
    void svgIsWellFormedWithOneRectPerBar() throws Exception {
        Label label = unitLabel();
        byte[] svg = new SvgLabelRenderer().render(List.of(label), SheetLayout.LABEL_PAGES);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(svg));
        assertThat(doc.getDocumentElement().getAttribute("width")).isEqualTo("50.8mm");
        int bars = (label.symbol().runs().length + 1) / 2;
        assertThat(doc.getElementsByTagName("rect").getLength()).isEqualTo(bars + 1);
        assertThat(new String(svg, StandardCharsets.UTF_8)).contains(">ATF3XK8P2QL</text>",
                ">AFROTRANSACT UNIT LABEL</text>", ">Ankara Wrap Dress</text>");
    }

    @Test
    void pdfHasOnePagePerPrintedLabelOrTiledSheets() throws IOException {
        PdfLabelRenderer pdf = new PdfLabelRenderer();
        try (PDDocument doc = Loader.loadPDF(pdf.render(List.of(caseLabel(), unitLabel()),
                SheetLayout.LABEL_PAGES))) {
            assertThat(doc.getNumberOfPages()).isEqualTo(3);
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isCloseTo(288f, Offset.offset(0.5f));
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("(01)09501101530003", "Ijebu gari 12", "ATF3XK8P2QL");
        }
        List<Label> many = Collections.nCopies(40, unitLabel());
        try (PDDocument doc = Loader.loadPDF(pdf.render(many, new SheetLayout(SheetLayout.Mode.SHEET,
                SheetLayout.Paper.LETTER)))) {
            // 2 × 1 in labels with 3 mm gaps on Letter: 3 per row, 9 rows = 27 per page.
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
        }
    }

    @Test
    void explicitSymbologyOverridesTheKindDefault() {
        Label label = policy.build(new LabelDraft(LabelKind.SHELF, Symbology.EAN_13, null, "4006381333931", null,
                null, null, null, null, null, null, null, "Shelf A12", List.of(), null));
        assertThat(ZplLabelRenderer.zpl(label)).contains("^BEN,", "^FD400638133393^FS");
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = LabelRenderersTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
