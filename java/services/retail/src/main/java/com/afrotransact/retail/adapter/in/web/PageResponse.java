package com.afrotransact.retail.adapter.in.web;

import java.util.List;
import java.util.function.Function;

import com.afrotransact.retail.port.Page;

/** A page of results for server-driven tables. */
record PageResponse<T>(List<T> items, long total, int page, int size) {

    static <D, T> PageResponse<T> of(Page<D> p, Function<D, T> mapper) {
        return new PageResponse<>(p.items().stream().map(mapper).toList(), p.total(), p.page(), p.size());
    }
}
