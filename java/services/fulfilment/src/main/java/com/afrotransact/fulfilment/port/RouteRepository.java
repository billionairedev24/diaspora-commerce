package com.afrotransact.fulfilment.port;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.DeliveryRoute;
import com.afrotransact.fulfilment.domain.RouteStatus;

/** Delivery routes with their stops and packages (one aggregate). */
public interface RouteRepository {

    void insert(DeliveryRoute route);

    /** Loads the route and locks its row until the transaction ends (driver events and dispatch serialise on it). */
    Optional<DeliveryRoute> lock(String routeId);

    Optional<DeliveryRoute> find(String routeId);

    /** Writes the route row (version + 1, optimistic check) and upserts its stops and packages. */
    void save(DeliveryRoute route);

    List<DeliveryRoute> list(LocalDate date, RouteStatus status, String driverId, int limit);

    /** Open routes (planned, assigned, in progress) of a driver. */
    List<DeliveryRoute> openForDriver(String driverId);

    /** Route id carrying a stop. */
    Optional<String> routeOfStop(String stopId);
}
