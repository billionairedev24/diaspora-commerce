package com.afrotransact.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A carton of an inbound shipment with its license plate ({@code LPN} + 8, scanned at the dock).
 *
 * @param contents units per SKU packed in the carton
 */
public record InboundBox(int boxNo, String lpn, List<Item> contents, BigDecimal weightLb, BigDecimal lengthIn,
                         BigDecimal widthIn, BigDecimal heightIn, Instant receivedAt) {

    public InboundBox {
        contents = contents == null ? List.of() : List.copyOf(contents);
    }

    public int units() {
        return contents.stream().mapToInt(Item::qty).sum();
    }

    public InboundBox received(Instant at) {
        return new InboundBox(boxNo, lpn, contents, weightLb, lengthIn, widthIn, heightIn, at);
    }

    /** Units of one SKU in the carton. */
    public record Item(String sku, int qty) {
    }
}
