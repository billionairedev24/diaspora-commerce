package com.afrotransact.identity.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.identity.domain.TourProgress;

/** Guided-tour progress, one row per user and tour. */
public interface TourProgressRepository {

    List<TourProgress> findAll(UUID userId);

    Optional<TourProgress> find(UUID userId, String tourId);

    long count(UUID userId);

    /** Inserts or replaces the row of the user and tour (atomic; the caller has decided that it wins). */
    void upsert(TourProgress progress);

    /** Removes one tour's row; true when there was one. */
    boolean delete(UUID userId, String tourId);

    /** Removes every tour row of the user; the number removed. */
    int deleteAll(UUID userId);
}
