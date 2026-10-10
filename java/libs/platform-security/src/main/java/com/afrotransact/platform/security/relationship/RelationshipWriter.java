package com.afrotransact.platform.security.relationship;

import java.util.List;

/**
 * Port: writes and deletes relationship tuples. Writes are idempotent (an existing tuple is not an error, nor is
 * deleting a missing one) and atomic per call (at most {@link #MAX_TUPLES_PER_CALL} tuples).
 *
 * <p>Call it after the owning transaction commits, or publish an event through the outbox and let
 * {@code authz-sync} write the tuple; never inside a database transaction that may still roll back.
 */
public interface RelationshipWriter {

    /** OpenFGA's default {@code maxTuplesPerWrite}. */
    int MAX_TUPLES_PER_CALL = 100;

    void write(List<RelationshipTuple> writes, List<RelationshipTuple> deletes);

    default void grant(RelationshipTuple... tuples) {
        write(List.of(tuples), List.of());
    }

    default void revoke(RelationshipTuple... tuples) {
        write(List.of(), List.of(tuples));
    }
}
