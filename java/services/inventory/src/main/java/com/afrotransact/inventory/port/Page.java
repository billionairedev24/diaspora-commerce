package com.afrotransact.inventory.port;

import java.util.List;
import java.util.function.Function;

/** One page of results. */
public record Page<T>(List<T> items, long total, int page, int size) {

    public Page {
        items = List.copyOf(items);
    }

    public <R> Page<R> map(Function<T, R> mapper) {
        return new Page<>(items.stream().map(mapper).toList(), total, page, size);
    }
}
