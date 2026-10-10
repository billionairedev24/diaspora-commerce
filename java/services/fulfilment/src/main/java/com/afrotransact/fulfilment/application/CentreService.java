package com.afrotransact.fulfilment.application;

import java.util.List;

import com.afrotransact.fulfilment.domain.FulfilmentCentre;

/** Fulfilment centres / nodes (depots of driver routes, sites of pick and pack). */
public interface CentreService {

    /** Creates or updates a centre (fulfilment:operate). */
    FulfilmentCentre save(FulfilmentCentre centre);

    List<FulfilmentCentre> list();

    FulfilmentCentre get(String id);
}
