package com.afrotransact.inventory.domain;

import java.util.List;
import java.util.stream.Collectors;

/** Not enough sellable stock for one or more lines; nothing was reserved (HTTP 409 inventory.insufficient-stock). */
public class InsufficientStockException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "inventory.insufficient-stock";

    private final transient List<Shortfall> shortfalls;

    public InsufficientStockException(List<Shortfall> shortfalls) {
        super("Insufficient stock for " + shortfalls.stream()
                .map(s -> s.sku() + " (requested " + s.requested() + ", available " + s.available() + ")")
                .collect(Collectors.joining(", ")));
        this.shortfalls = List.copyOf(shortfalls);
    }

    public List<Shortfall> shortfalls() {
        return shortfalls;
    }
}
