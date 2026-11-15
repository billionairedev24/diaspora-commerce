package com.afrotransact.offers.adapter.in.module;

import java.util.Optional;

import com.afrotransact.offers.api.SellerStandingApi;
import com.afrotransact.offers.application.SellerPerformanceCommand;
import com.afrotransact.offers.application.SellerStandingService;
import com.afrotransact.platform.security.ActorResolver;
import com.afrotransact.platform.security.ModuleEntry;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Component;

/**
 * The in-process entry of {@link SellerStandingApi}: the use cases behind the seller standing read and the performance push
 * under the calling module's principal; {@link SellerStandingService} checks the permissions as for a token.
 */
@Component
class SellerStandingApiAdapter implements SellerStandingApi {

    private final SellerStandingService standings;
    private final ModuleEntry entry;

    SellerStandingApiAdapter(SellerStandingService standings, ActorResolver actors) {
        this.standings = standings;
        this.entry = new ModuleEntry(actors);
    }

    @Override
    public Optional<Double> rating(String orgId) {
        return entry.run(() -> {
            try {
                return Optional.ofNullable(standings.get(orgId).rating());
            } catch (NotFoundException notFound) {
                return Optional.<Double>empty();
            }
        }, CheckoutFailures::map);
    }

    @Override
    public void updatePerformance(String orgId, Double rating, Double onTimeRate, Double defectRate, boolean restricted) {
        entry.runVoid(() -> standings.updatePerformance(orgId, new SellerPerformanceCommand(rating, onTimeRate, defectRate,
                restricted)), CheckoutFailures::map);
    }
}
