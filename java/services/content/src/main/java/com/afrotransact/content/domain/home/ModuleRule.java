package com.afrotransact.content.domain.home;

/**
 * One staff-editable row of the dynamic home (docs/engineering/dynamic-home.md). Zero means "no requirement".
 *
 * @param enabled         staff switch
 * @param minProducts     eligible products the module needs (or providers, for services)
 * @param minSellers      distinct sellers those products need
 * @param minUnits        departments or communities the module needs
 * @param unitMinProducts a department or community counts only with this many eligible products
 * @param unitMinSellers  and this many distinct sellers
 * @param maxAgeDays      newest eligible product must be at most this old (0 = no limit)
 * @param weight          order inside the module's band, higher first (0 to 100)
 * @param show            items the module shows (its variety pool is poolMultiplier times this)
 * @param band            hero, personal, discovery, promo or brand
 */
public record ModuleRule(boolean enabled, int minProducts, int minSellers, int minUnits, int unitMinProducts,
                         int unitMinSellers, int maxAgeDays, int weight, int show, String band) {
}
