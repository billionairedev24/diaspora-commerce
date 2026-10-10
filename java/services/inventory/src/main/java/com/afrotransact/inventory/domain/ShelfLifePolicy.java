package com.afrotransact.inventory.domain;

import java.time.LocalDate;

/** Decides at receipt whether a lot is sellable, must be held back (short-dated) or rejected (expired / bad dates). */
public interface ShelfLifePolicy {

    /**
     * @param shelfLifeDays total shelf life from manufacture; when null it is derived from the two dates
     * @param today         node-local receipt date
     */
    ShelfLifeDecision assess(LocalDate mfgDate, LocalDate expDate, Integer shelfLifeDays, LocalDate today);
}
