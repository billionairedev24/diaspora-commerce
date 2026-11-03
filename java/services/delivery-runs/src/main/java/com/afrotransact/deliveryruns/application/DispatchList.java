package com.afrotransact.deliveryruns.application;

import java.util.List;

import com.afrotransact.deliveryruns.domain.GeoPoint;
import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;

/**
 * What fulfilment plans one sweep from (S264): the confirmed run, its location, its confirmed orders (with how far each
 * has got) and where the run leaves from (the hub: the location's origin, else the service default).
 */
public record DispatchList(Run run, Location location, List<RunOrder> orders, GeoPoint origin) {
}
