package com.afrotransact.catalog.domain.barcode;

/**
 * PDF page layout.
 *
 * @param mode  {@link Mode#LABEL}: one label per page sized to the label (label printers); {@link Mode#SHEET}: labels
 *              tiled on paper with cut lines (office printers)
 * @param paper paper size for {@link Mode#SHEET}
 */
public record SheetLayout(Mode mode, Paper paper) {

    public static final SheetLayout LABEL_PAGES = new SheetLayout(Mode.LABEL, Paper.LETTER);

    public SheetLayout {
        mode = mode == null ? Mode.LABEL : mode;
        paper = paper == null ? Paper.LETTER : paper;
    }

    /** Page per label, or tiled sheet. */
    public enum Mode {
        LABEL, SHEET
    }

    /** Paper sizes in millimetres. */
    public enum Paper {
        LETTER(215.9, 279.4), A4(210, 297);

        private final double widthMm;
        private final double heightMm;

        Paper(double widthMm, double heightMm) {
            this.widthMm = widthMm;
            this.heightMm = heightMm;
        }

        public double widthMm() {
            return widthMm;
        }

        public double heightMm() {
            return heightMm;
        }
    }
}
