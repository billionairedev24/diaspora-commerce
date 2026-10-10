package com.afrotransact.catalog.domain.barcode;

import java.util.List;

/** Code 128, GS1-128 and EAN-13 encoder written against ISO/IEC 15417 and the GS1 General Specifications. */
public class DefaultBarcodeEncoder implements BarcodeEncoder {

    @Override
    public BarcodeSymbol code128(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Code 128 needs at least one character");
        }
        List<Integer> cw = Code128.codewords(text.chars().toArray());
        return new BarcodeSymbol(Symbology.CODE_128, text, text, Code128.modules(cw), Code128.QUIET_ZONE, cw);
    }

    @Override
    public BarcodeSymbol gs1128(Gs1ElementString elements) {
        List<Integer> cw = Code128.codewords(elements.carrierInput());
        return new BarcodeSymbol(Symbology.GS1_128, elements.transmitted(), elements.humanReadable(),
                Code128.modules(cw), Code128.QUIET_ZONE, cw);
    }

    @Override
    public BarcodeSymbol ean13(String gtin) {
        String digits = Ean13.digits(gtin);
        return new BarcodeSymbol(Symbology.EAN_13, digits, digits, Ean13.modules(digits), Ean13.QUIET_ZONE, List.of());
    }
}
