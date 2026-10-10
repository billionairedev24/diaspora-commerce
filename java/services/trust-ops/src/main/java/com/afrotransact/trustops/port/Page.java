package com.afrotransact.trustops.port;

import java.util.List;
import java.util.function.Function;

/** A page of results. */
public record Page<T>(List<T> items, long total, int page, int size) {

    public Page {
        items = List.copyOf(items);
    }

    public <R> Page<R> map(Function<T, R> f) {
        return new Page<>(items.stream().map(f).toList(), total, page, size);
    }
}
