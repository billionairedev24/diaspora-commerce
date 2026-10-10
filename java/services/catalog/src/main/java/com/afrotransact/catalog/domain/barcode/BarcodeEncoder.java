package com.afrotransact.catalog.domain.barcode;

/** Encodes data into bar patterns. */
public interface BarcodeEncoder {

    /** Code 128 of printable ASCII text (ATINs, AT-FN codes, SKUs, bins). */
    BarcodeSymbol code128(String text);

    /** GS1-128 of a validated element string (FNC1 start, FNC1 separators after variable-length AIs). */
    BarcodeSymbol gs1128(Gs1ElementString elements);

    /** EAN-13 of a GTIN-12 (UPC-A, zero-padded) or GTIN-13. */
    BarcodeSymbol ean13(String gtin);
}
