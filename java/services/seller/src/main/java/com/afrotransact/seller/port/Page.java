package com.afrotransact.seller.port;

import java.util.List;

/** One page of results. */
public record Page<T>(List<T> items, int page, int size, long total) {

    public Page {
        items = List.copyOf(items);
    }
}
