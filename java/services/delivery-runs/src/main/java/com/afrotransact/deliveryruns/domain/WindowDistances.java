package com.afrotransact.deliveryruns.domain;

import java.util.OptionalDouble;

/**
 * How far a pre-order window delivers from the hub: the road miles from the run origin to the centre of the location
 * the window is planned for. Implemented by the application with geo-service; empty when it cannot be measured.
 */
@FunctionalInterface
public interface WindowDistances {

    /** Never measured (tests, windows without a location). */
    WindowDistances UNKNOWN = w -> OptionalDouble.empty();

    OptionalDouble miles(PreorderWindow window);
}
