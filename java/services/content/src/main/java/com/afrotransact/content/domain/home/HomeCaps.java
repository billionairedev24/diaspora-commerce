package com.afrotransact.content.domain.home;

/**
 * Page and variety limits staff can change.
 *
 * @param maxModules         most modules on the page
 * @param minModules         fewest modules the page aims for
 * @param sellerShare        most items of one seller in one module
 * @param sponsoredPerOrganic at most one sponsored module per this many organic ones
 * @param poolMultiplier     a module draws from a pool this many times what it shows
 * @param noveltyLoads       previous home loads remembered
 * @param noveltyPenalty     weight removed from a remembered item (0.6 keeps 40 percent of its chance)
 * @param visitWindowMinutes inactivity that starts a new visit
 * @param hysteresis         a shown module stays shown until this far below its minimum
 * @param dealMinPercent     smallest discount a deal shows
 * @param navBarSize         departments in the header bar
 */
public record HomeCaps(int maxModules, int minModules, int sellerShare, int sponsoredPerOrganic, int poolMultiplier,
                       int noveltyLoads, double noveltyPenalty, int visitWindowMinutes, int hysteresis,
                       int dealMinPercent, int navBarSize) {

    public static HomeCaps defaults() {
        return new HomeCaps(12, 3, 2, 3, 3, 3, 0.6, 30, 2, 10, 7);
    }
}
