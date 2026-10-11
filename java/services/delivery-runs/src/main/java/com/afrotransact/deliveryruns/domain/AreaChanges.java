package com.afrotransact.deliveryruns.domain;

import java.util.List;

/**
 * What a region edit does to the stored coverage rows.
 *
 * @param added    new rows
 * @param removed  rows deleted
 * @param switched existing rows whose switch flips (with their stored version)
 */
public record AreaChanges(List<CoverageArea> added, List<CoverageArea> removed, List<CoverageArea> switched) {

    public AreaChanges {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
        switched = List.copyOf(switched);
    }

    public boolean empty() {
        return added.isEmpty() && removed.isEmpty() && switched.isEmpty();
    }
}
