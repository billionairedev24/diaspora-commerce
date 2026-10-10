package com.afrotransact.orders.application;

import java.util.List;

/** A page of results with an opaque cursor for the next page (null on the last page). */
public record OrderPage<T>(List<T> items, String nextCursor, long total) {
}
