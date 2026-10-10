package com.afrotransact.orders.domain;

/** Creates human order numbers such as {@code AT-2026-7KQ3MD}. */
public interface OrderNumberGenerator {

    String next(int year);
}
