package com.afrotransact.content.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.placement.Slot;
import com.afrotransact.content.domain.placement.SlotKey;

/** Slot catalogue. */
public interface SlotRepository {

    List<Slot> findAll();

    Optional<Slot> find(SlotKey key);
}
