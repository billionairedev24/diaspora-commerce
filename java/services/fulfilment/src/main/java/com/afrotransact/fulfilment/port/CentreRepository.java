package com.afrotransact.fulfilment.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.fulfilment.domain.FulfilmentCentre;

/** Fulfilment centres. */
public interface CentreRepository {

    FulfilmentCentre save(FulfilmentCentre centre);

    Optional<FulfilmentCentre> find(String id);

    List<FulfilmentCentre> list();
}
