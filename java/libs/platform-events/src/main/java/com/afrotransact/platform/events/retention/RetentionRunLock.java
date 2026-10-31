package com.afrotransact.platform.events.retention;

import java.util.Optional;
import java.util.function.Supplier;

/** Makes sure only one instance runs the purge at a time. */
public interface RetentionRunLock {

    /**
     * Runs {@code action} when the lock is free and releases it afterwards.
     *
     * @return the action's result, or empty when another holder has the lock
     */
    <T> Optional<T> tryRun(Supplier<T> action);
}
