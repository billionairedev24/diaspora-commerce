package com.afrotransact.deliveryruns.application;

import java.util.List;

import com.afrotransact.deliveryruns.domain.Location;
import com.afrotransact.deliveryruns.domain.Run;
import com.afrotransact.deliveryruns.domain.RunOrder;

/** What fulfilment plans one sweep from (S264): the confirmed run, its location and its confirmed orders. */
public record DispatchList(Run run, Location location, List<RunOrder> orders) {
}
