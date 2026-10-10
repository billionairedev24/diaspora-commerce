package com.afrotransact.orders.port;

import java.util.List;

/** One page of a list query. */
public record Page<T>(List<T> items, int offset, int limit, long total) {

    public Page {
        items = List.copyOf(items);
    }

    public boolean hasMore() {
        return offset + items.size() < total;
    }
}
