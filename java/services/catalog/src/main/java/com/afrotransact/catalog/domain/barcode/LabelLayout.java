package com.afrotransact.catalog.domain.barcode;

import java.util.ArrayList;
import java.util.List;

/**
 * Where everything sits on a label, in millimetres from the top-left corner, so the SVG, PDF and ZPL renderers draw
 * the same label. Text sizes are em heights; baselines are y positions.
 *
 * @param moduleWidth width of one bar module
 * @param barsX       left edge of the first bar (the quiet zone is to its left)
 */
public record LabelLayout(double width, double height, double margin, List<Text> texts, double barsX, double barsY,
                          double moduleWidth, double barHeight, Text humanReadable) {

    /** Widest module we draw (larger labels keep a sensible symbol width). */
    public static final double MAX_MODULE_MM = 0.5;
    private static final double MIN_BAR_HEIGHT_MM = 6;
    private static final double CHAR_WIDTH_EM = 0.6;

    public LabelLayout {
        texts = List.copyOf(texts);
    }

    /** A line of text. {@code centered}: x is the centre, otherwise the left edge. */
    public record Text(String text, double x, double baseline, double size, boolean bold, boolean centered) {
    }

    /** Margin for a label of this height. */
    public static double margin(double heightMm) {
        return heightMm >= 60 ? 4 : 2;
    }

    public static LabelLayout of(Label label) {
        double w = label.widthMm();
        double h = label.heightMm();
        double m = margin(h);
        double title = Math.clamp(h * 0.11, 2.4, 6.0);
        String hrText = label.symbol().humanReadable();
        double hrSize = Math.min(title * 0.85, (w - 2 * m) / (Math.max(hrText.length(), 1) * CHAR_WIDTH_EM));
        int keepLines = label.lines().size();
        boolean keepCaption = label.caption() != null && !label.caption().isBlank();
        while (true) {
            List<Text> texts = new ArrayList<>();
            double y = m;
            if (keepCaption) {
                double s = title * 0.7;
                y += s;
                texts.add(new Text(fit(label.caption(), s, w - 2 * m), m, y, s, true, false));
                y += 0.6;
            }
            if (!label.title().isEmpty()) {
                y += title;
                texts.add(new Text(fit(label.title(), title, w - 2 * m), m, y, title, true, false));
                y += 0.6;
            }
            for (int i = 0; i < keepLines; i++) {
                double s = shrink(label.lines().get(i), title * 0.8, title * 0.5, w - 2 * m);
                y += s;
                texts.add(new Text(fit(label.lines().get(i), s, w - 2 * m), m, y, s, false, false));
                y += 0.4;
            }
            double barsY = y + 1.0;
            double available = h - m - hrSize - 0.6 - barsY;
            if (available >= MIN_BAR_HEIGHT_MM || keepLines == 0 && !keepCaption) {
                double barHeight = Math.max(Math.min(available, Math.max(15, w * 0.35)), 1);
                BarcodeSymbol symbol = label.symbol();
                double module = Math.min(MAX_MODULE_MM, (w - 2 * m) / symbol.totalModules());
                double barsX = (w - symbol.modules().length() * module) / 2;
                Text hr = new Text(hrText, w / 2, barsY + barHeight + 0.6 + hrSize * 0.75, hrSize,
                        false, true);
                return new LabelLayout(w, h, m, texts, barsX, barsY, module, barHeight, hr);
            }
            if (keepLines > 0) {
                keepLines--;
            } else {
                keepCaption = false;
            }
        }
    }

    /** The largest size between {@code min} and {@code max} at which {@code text} fits {@code widthMm}. */
    static double shrink(String text, double max, double min, double widthMm) {
        return Math.clamp(widthMm / (Math.max(text.length(), 1) * CHAR_WIDTH_EM), min, max);
    }

    /** Cuts {@code text} to what fits in {@code widthMm} at {@code size} (monospace estimate), with an ellipsis. */
    static String fit(String text, double size, double widthMm) {
        int max = Math.max(4, (int) Math.floor(widthMm / (size * CHAR_WIDTH_EM)));
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
