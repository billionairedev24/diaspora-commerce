package com.afrotransact.catalog.testsupport;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import com.afrotransact.catalog.domain.barcode.BarcodeSymbol;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

/** Decodes generated barcodes with ZXing, the way a scanner would, to prove they scan. */
public final class BarcodeScanner {

    private BarcodeScanner() {
    }

    /** Decodes a PNG. GS1-128 results start with the {@code ]C1} symbology identifier and use GS separators. */
    public static Result scanPng(byte[] png, BarcodeFormat format) {
        try {
            return scan(ImageIO.read(new ByteArrayInputStream(png)), format);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Draws the symbol's modules (2 px each, quiet zones included) and decodes them. */
    public static Result scan(BarcodeSymbol symbol, BarcodeFormat format) {
        int module = 2;
        int width = symbol.totalModules() * module;
        int height = 60;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            int m = x / module - symbol.quietZone();
            boolean bar = m >= 0 && m < symbol.modules().length() && symbol.modules().charAt(m) == '1';
            for (int y = 0; y < height; y++) {
                img.setRGB(x, y, bar ? 0x000000 : 0xFFFFFF);
            }
        }
        return scan(img, format);
    }

    private static Result scan(BufferedImage img, BarcodeFormat format) {
        int w = img.getWidth();
        int h = img.getHeight();
        LuminanceSource source = new RGBLuminanceSource(w, h, img.getRGB(0, 0, w, h, null, 0, w));
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, List.of(format));
        hints.put(DecodeHintType.ASSUME_GS1, Boolean.TRUE);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        try {
            return new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
        } catch (NotFoundException e) {
            throw new AssertionError("ZXing could not decode the " + format + " barcode", e);
        }
    }
}
