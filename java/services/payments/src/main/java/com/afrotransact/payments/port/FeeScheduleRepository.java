package com.afrotransact.payments.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.afrotransact.payments.domain.FeeSchedule;

/** Versioned fee schedules (rows are immutable; a change is a new version). */
public interface FeeScheduleRepository {

    void insert(FeeSchedule schedule);

    List<FeeSchedule> all();

    Optional<FeeSchedule> findById(UUID id);

    Optional<FeeSchedule> latest();
}
