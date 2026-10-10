package com.afrotransact.catalog.adapter.out.labels;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import javax.imageio.ImageIO;

import com.afrotransact.catalog.domain.barcode.BarcodeSymbol;
import com.afrotransact.catalog.domain.barcode.Label;
import com.afrotransact.catalog.domain.barcode.LabelFormat;
import com.afrotransact.catalog.domain.barcode.SheetLayout;
import com.afrotransact.catalog.port.LabelRenderer;
import org.springframework.stereotype.Component;

/**
 * The bare barcode symbol as a 1-bit PNG: {@value #MODULE_PX} px per module, quiet zones included, no text (so it
 * needs no fonts on the server). Use SVG, PDF or ZPL for a full label with title and human-readable line.
 */
@Component
class PngLabelRenderer implements LabelRenderer {

    static final int MODULE_PX = 3;
    static final int BAR_HEIGHT_PX = 120;
    private static final int PADDING_PX = 8;

    @Override
    public LabelFormat format() {
        return LabelFormat.PNG;
    }

    @Override
    public String contentType() {
        return "image/png";
    }

    @Override
    public boolean supportsBatch() {
        return false;
    }

    @Override
    public byte[] render(List<Label> labels, SheetLayout layout) {
        BarcodeSymbol symbol = labels.getFirst().symbol();
        int width = symbol.totalModules() * MODULE_PX;
        int height = BAR_HEIGHT_PX + 2 * PADDING_PX;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(Color.BLACK);
            String modules = symbol.modules();
            int x0 = symbol.quietZone() * MODULE_PX;
            for (int i = 0; i < modules.length(); i++) {
                if (modules.charAt(i) == '1') {
                    g.fillRect(x0 + i * MODULE_PX, PADDING_PX, MODULE_PX, BAR_HEIGHT_PX);
                }
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
