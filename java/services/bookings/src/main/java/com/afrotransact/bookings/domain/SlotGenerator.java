package com.afrotransact.bookings.domain;

import java.util.List;

/** Generates bookable start times from working hours, exceptions, booking rules and existing appointments. */
public interface SlotGenerator {

    List<Slot> slots(SlotQuery query);
}
