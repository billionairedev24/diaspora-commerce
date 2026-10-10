package com.afrotransact.catalog.adapter.out.labels;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import com.afrotransact.catalog.domain.barcode.Label;
import com.afrotransact.catalog.domain.barcode.LabelFormat;
import com.afrotransact.catalog.domain.barcode.LabelLayout;
import com.afrotransact.catalog.domain.barcode.LabelLayout.Text;
import com.afrotransact.catalog.domain.barcode.SheetLayout;
import com.afrotransact.catalog.port.LabelRenderer;
import org.springframework.stereotype.Component;

/** One label as SVG in millimetre units (vector bars, so it prints sharp at any size). */
@Component
class SvgLabelRenderer implements LabelRenderer {

    @Override
    public LabelFormat format() {
        return LabelFormat.SVG;
    }

    @Override
    public String contentType() {
        return "image/svg+xml";
    }

    @Override
    public boolean supportsBatch() {
        return false;
    }

    @Override
    public byte[] render(List<Label> labels, SheetLayout layout) {
        return svg(labels.getFirst()).getBytes(StandardCharsets.UTF_8);
    }

    static String svg(Label label) {
        LabelLayout l = LabelLayout.of(label);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(n(l.width())).append("mm\" height=\"")
                .append(n(l.height())).append("mm\" viewBox=\"0 0 ").append(n(l.width())).append(' ')
                .append(n(l.height())).append("\" shape-rendering=\"crispEdges\">\n")
                .append("<title>").append(escape(label.symbol().humanReadable())).append("</title>\n")
                .append("<rect width=\"").append(n(l.width())).append("\" height=\"").append(n(l.height()))
                .append("\" fill=\"#ffffff\"/>\n");
        for (Text t : l.texts()) {
            text(sb, t, "Helvetica, Arial, sans-serif");
        }
        sb.append("<g fill=\"#000000\">\n");
        int[] runs = label.symbol().runs();
        double x = l.barsX();
        for (int i = 0; i < runs.length; i++) {
            double w = runs[i] * l.moduleWidth();
            if (i % 2 == 0) {
                sb.append("<rect x=\"").append(n(x)).append("\" y=\"").append(n(l.barsY())).append("\" width=\"")
                        .append(n(w)).append("\" height=\"").append(n(l.barHeight())).append("\"/>\n");
            }
            x += w;
        }
        sb.append("</g>\n");
        text(sb, l.humanReadable(), "'Courier New', Courier, monospace");
        return sb.append("</svg>\n").toString();
    }

    private static void text(StringBuilder sb, Text t, String family) {
        sb.append("<text x=\"").append(n(t.x())).append("\" y=\"").append(n(t.baseline())).append("\" font-family=\"")
                .append(family).append("\" font-size=\"").append(n(t.size())).append('"');
        if (t.bold()) {
            sb.append(" font-weight=\"700\"");
        }
        if (t.centered()) {
            sb.append(" text-anchor=\"middle\"");
        }
        sb.append('>').append(escape(t.text())).append("</text>\n");
    }

    /** Millimetres with up to 3 decimals, no trailing zeros. */
    static String n(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        s = s.contains(".") ? s.replaceAll("0+$", "") : s;
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\t') {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
