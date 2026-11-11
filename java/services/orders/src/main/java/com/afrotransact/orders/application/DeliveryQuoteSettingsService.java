package com.afrotransact.orders.application;

import java.util.List;

import com.afrotransact.orders.domain.QuoteSettings;
import com.afrotransact.orders.port.DeliveryQuoteSettingsRepository.Change;

/** SLA, escalation, hand-out, amount bounds and validity of delivery quotes. */
public interface DeliveryQuoteSettingsService {

    /** The settings, the people named in the escalation chain, and the last changes. Anyone who may view quotes. */
    Current current();

    /** Changes them; needs {@code delivery-quotes:assign}, a reason (5+ characters), is audited. */
    Current update(QuoteSettings next, String reason, String actorName);

    /**
     * @param level1People names of the people (and role holders) level 1 goes to
     * @param level2People the same for level 2
     */
    record Current(QuoteSettings settings, List<String> level1People, List<String> level2People,
                   List<Change> changes) {
    }
}
