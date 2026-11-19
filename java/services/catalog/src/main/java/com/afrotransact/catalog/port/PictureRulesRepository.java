package com.afrotransact.catalog.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.catalog.domain.pictures.PictureRules;
import com.afrotransact.catalog.domain.pictures.PictureRulesChange;

/** The staff-saved picture rules (one row) and the audit trail of every change. Absent until staff save for the first time. */
public interface PictureRulesRepository {

    Optional<PictureRules> find();

    /**
     * Stores {@code next} when {@code expectedVersion} is the stored version (0 when nothing is stored yet) and appends the
     * change to the audit trail in the same transaction.
     *
     * @throws com.afrotransact.catalog.domain.StaleVersionException when somebody saved in between
     */
    PictureRules save(PictureRules next, long expectedVersion, String reason);

    /** The newest changes first. */
    List<PictureRulesChange> history(int limit);
}
