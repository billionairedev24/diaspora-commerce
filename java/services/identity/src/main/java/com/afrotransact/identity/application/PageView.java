package com.afrotransact.identity.application;

import java.util.List;

/** A page of results. */
public record PageView<T>(List<T> items, int page, int size, long total) {
}
