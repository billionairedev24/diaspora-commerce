package com.afrotransact.fulfilment.application;

import java.util.List;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.FulfilmentConflict;
import com.afrotransact.fulfilment.port.RouteRepository;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DriverQueryServiceImpl implements DriverQueryService {

    private final DriverAccessService access;
    private final RouteRepository routes;

    DriverQueryServiceImpl(DriverAccessService access, RouteRepository routes) {
        this.access = access;
        this.routes = routes;
    }

    @Override
    @Transactional
    public List<DeliveryRoute> myRoutes(DriverCredential credential) {
        return routes.openForDriver(active(credential).driverId());
    }

    @Override
    @Transactional
    public DeliveryRoute myRoute(DriverCredential credential, String routeId) {
        DriverContext driver = active(credential);
        DeliveryRoute route = routes.find(routeId).orElseThrow(() -> new NotFoundException(
                "fulfilment.route-not-found", "Route " + routeId + " not found"));
        if (!driver.driverId().equals(route.driverId())) {
            throw new FulfilmentConflict("fulfilment.not-your-route", "Route " + routeId + " is not assigned to you");
        }
        return route;
    }

    @Override
    @Transactional
    public String driverId(DriverCredential credential) {
        return access.resolve(credential).driverId();
    }

    private DriverContext active(DriverCredential credential) {
        DriverContext driver = access.resolve(credential);
        if (driver.signOut()) {
            throw DriverAccessServiceImpl.revoked(driver.access());
        }
        return driver;
    }
}
