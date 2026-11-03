package com.afrotransact.taxreturns.port;

import java.util.List;

import com.afrotransact.taxreturns.domain.tax.NexusSetting;

/** What staff recorded per state. */
public interface TaxNexusRepository {

    List<NexusSetting> all();

    void save(NexusSetting setting);
}
