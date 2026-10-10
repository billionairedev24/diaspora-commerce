package com.afrotransact.retail.port;

import java.util.List;

/** One page of a repository query. */
public record Page<T>(List<T> items, long total, int page, int size) {

    public Page {
        items = List.copyOf(items);
    }
}
