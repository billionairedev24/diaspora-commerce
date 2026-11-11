package com.afrotransact.identity.adapter.in.web;

import java.util.Map;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;

/** Renders an otpauth URI as an inline SVG (one path, no styles or scripts, so the hosted pages' CSP stays strict). */
final class QrCodes {

    private static final int QUIET_ZONE = 2;

    private QrCodes() {
    }

    static String svg(String content, String label) {
        QRCode code;
        try {
            code = Encoder.encode(content, ErrorCorrectionLevel.M, Map.of(EncodeHintType.CHARACTER_SET, "UTF-8"));
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot encode QR code", e);
        }
        ByteMatrix m = code.getMatrix();
        int size = m.getWidth() + 2 * QUIET_ZONE;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                if (m.get(x, y) == 1) {
                    path.append('M').append(x + QUIET_ZONE).append(' ').append(y + QUIET_ZONE).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + ' ' + size
                + "\" role=\"img\" aria-label=\"" + label.replace("\"", "") + "\" shape-rendering=\"crispEdges\">"
                + "<rect width=\"" + size + "\" height=\"" + size + "\" fill=\"#ffffff\"/>"
                + "<path d=\"" + path + "\" fill=\"#000000\"/></svg>";
    }
}
