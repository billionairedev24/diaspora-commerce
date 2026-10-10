package com.afrotransact.catalog.adapter.out.labels;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.afrotransact.catalog.domain.barcode.BarcodeSymbol;
import com.afrotransact.catalog.domain.barcode.Label;
import com.afrotransact.catalog.domain.barcode.LabelFormat;
import com.afrotransact.catalog.domain.barcode.LabelLayout;
import com.afrotransact.catalog.domain.barcode.LabelLayout.Text;
import com.afrotransact.catalog.domain.barcode.SheetLayout;
import com.afrotransact.catalog.domain.barcode.Symbology;
import com.afrotransact.catalog.port.LabelRenderer;
import org.springframework.stereotype.Component;

/**
 * Zebra Programming Language for 203 dpi printers (8 dots/mm), one {@code ^XA…^XZ} block per label with
 * {@code ^PQ} copies. Code 128 and GS1-128 use {@code ^BC} in mode N with explicit subset invocation codes taken from
 * our own encoding ({@code >;} start C, {@code >:} start B, {@code >5}/{@code >6} switch, {@code >8} FNC1), so the
 * printer draws exactly the bars our SVG / PDF show; EAN-13 uses {@code ^BE} with the first 12 digits. Text fields use
 * {@code ^FH_} hex escapes for {@code ^}, {@code ~} and {@code _}, and {@code ^CI28} (UTF-8).
 */
@Component
class ZplLabelRenderer implements LabelRenderer {

    static final int DOTS_PER_MM = 8;

    @Override
    public LabelFormat format() {
        return LabelFormat.ZPL;
    }

    @Override
    public String contentType() {
        return "text/plain;charset=UTF-8";
    }

    @Override
    public boolean supportsBatch() {
        return true;
    }

    @Override
    public byte[] render(List<Label> labels, SheetLayout layout) {
        StringBuilder sb = new StringBuilder();
        for (Label label : labels) {
            sb.append(zpl(label));
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String zpl(Label label) {
        LabelLayout l = LabelLayout.of(label);
        BarcodeSymbol symbol = label.symbol();
        int width = dots(l.width());
        StringBuilder sb = new StringBuilder();
        sb.append("^XA\n^CI28\n^PW").append(width).append("\n^LL").append(dots(l.height())).append("\n^LH0,0\n");
        for (Text t : l.texts()) {
            text(sb, t, width);
        }
        int module = Math.max(1, (int) Math.floor(l.moduleWidth() * DOTS_PER_MM));
        int barsX = Math.max(0, (width - symbol.modules().length() * module) / 2);
        int barHeight = dots(l.barHeight());
        sb.append("^FO").append(barsX).append(',').append(dots(l.barsY())).append("^BY").append(module);
        if (symbol.symbology() == Symbology.EAN_13) {
            sb.append("^BEN,").append(barHeight).append(",N,N^FD").append(symbol.data(), 0, 12).append("^FS\n");
        } else {
            sb.append("^BCN,").append(barHeight).append(",N,N,N,N^FH_^FD").append(code128Data(symbol.codewords()))
                    .append("^FS\n");
        }
        text(sb, l.humanReadable(), width);
        sb.append("^PQ").append(label.copies()).append("\n^XZ\n");
        return sb.toString();
    }

    /** ^BC mode N field data reproducing our codewords (the printer adds the check character and stop). */
    static String code128Data(List<Integer> codewords) {
        StringBuilder sb = new StringBuilder();
        boolean setC = codewords.getFirst() == 105;
        sb.append(setC ? ">;" : ">:");
        for (int i = 1; i < codewords.size() - 1; i++) {
            int cw = codewords.get(i);
            if (cw == 102) {
                sb.append(">8");
            } else if (setC && cw == 100) {
                sb.append(">6");
                setC = false;
            } else if (!setC && cw == 99) {
                sb.append(">5");
                setC = true;
            } else if (setC) {
                sb.append(cw < 10 ? "0" : "").append(cw);
            } else {
                char c = (char) (cw + 32);
                sb.append(c == '>' ? "><" : hex(String.valueOf(c)));
            }
        }
        return sb.toString();
    }

    private static void text(StringBuilder sb, Text t, int labelWidth) {
        int size = Math.max(12, dots(t.size()));
        int top = Math.max(0, dots(t.baseline() - t.size() * 0.8));
        if (t.centered()) {
            sb.append("^FO0,").append(top).append("^FB").append(labelWidth).append(",1,0,C");
        } else {
            sb.append("^FO").append(dots(t.x())).append(',').append(top);
        }
        sb.append("^A0N,").append(size).append(',').append(size).append("^FH_^FD").append(hex(t.text()))
                .append("^FS\n");
    }

    /** ^FH_ escapes for characters that would end the field or start a command. */
    static String hex(String s) {
        return s.replace("_", "_5F").replace("^", "_5E").replace("~", "_7E");
    }

    private static int dots(double mm) {
        return (int) Math.round(mm * DOTS_PER_MM);
    }
}
