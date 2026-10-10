package com.afrotransact.offers.application;

import java.util.List;

/** A page of results returned by use cases. */
public record PageView<T>(List<T> items, long total, int page, int size) {

    public PageView {
        items = List.copyOf(items);
    }
}
