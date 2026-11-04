package com.afrotransact.catalog.domain.brand;

/**
 * A brand with how much it is used, for the staff review queue.
 *
 * @param products listings holding the brand (rejected ones excluded)
 * @param sellers  distinct seller organisations behind them
 */
public record BrandUsage(Brand brand, long products, long sellers) {
}
