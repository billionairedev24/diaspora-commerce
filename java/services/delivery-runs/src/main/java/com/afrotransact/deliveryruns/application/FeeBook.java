package com.afrotransact.deliveryruns.application;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.OptionalDouble;

import com.afrotransact.deliveryruns.domain.DeliveryFees;
import com.afrotransact.deliveryruns.domain.FeeSnapshot;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.port.DeliveryFeeRuleRepository;
import org.springframework.stereotype.Component;

/** The delivery fee rules as they stand now, ready to resolve a fee (see {@link DeliveryFees}). */
@Component
class FeeBook {

    private final DeliveryFeeRuleRepository rules;
    private final GeoWindowDistances distances;

    FeeBook(DeliveryFeeRuleRepository rules, GeoWindowDistances distances) {
        this.rules = rules;
        this.distances = distances;
    }

    DeliveryFees current() {
        return new DeliveryFees(rules.findAll(), distances);
    }

    /**
     * The delivery fee for a run at this location: the AfroTransact delivery rule band for its distance (the
     * farthest band when geo-service cannot measure it); empty when there is no rule or the location is beyond it.
     */
    Optional<FeeSnapshot> doorFeeAt(Location location, String currency) {
        OptionalDouble miles = distances.miles(location);
        return current().door(currency, miles.isPresent() ? BigDecimal.valueOf(miles.getAsDouble()) : null);
    }

    GeoWindowDistances distances() {
        return distances;
    }
}
