package com.afrotransact.payments.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.FeeSchedule;

/** Versioned fee schedules (rows are immutable; a change is a new version, a withdrawal a cancellation row). */
public interface FeeScheduleRepository {

    void insert(FeeSchedule schedule);

    /** Records the cancellation of a scheduled version (append-only; the version row itself is never changed). */
    void insertCancellation(UUID scheduleId, FeeSchedule.Cancellation cancellation);

    List<FeeSchedule> all();

    Optional<FeeSchedule> findById(UUID id);

    Optional<FeeSchedule> latest();
}
