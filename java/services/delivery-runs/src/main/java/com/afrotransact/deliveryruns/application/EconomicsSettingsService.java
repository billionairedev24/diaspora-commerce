package com.afrotransact.deliveryruns.application;

import java.util.List;

import com.afrotransact.deliveryruns.domain.EconomicsChange;
import com.afrotransact.deliveryruns.domain.EconomicsRevision;

/**
 * The driver cost model behind run economics, staff-editable (S265). Reads need {@code delivery-runs:read}; changes
 * {@code preorders:manage} (as delivery fees) and a reason, logged with who and when.
 */
public interface EconomicsSettingsService {

    /** The settings in force: the staff's last save, else the environment's defaults (version 0). */
    EconomicsRevision current();

    /**
     * Saves new numbers as the next version. {@code expectedVersion} is the version the caller saw (null = any; 0 =
     * the defaults). 422 {@code delivery-runs.reason-required} without a reason and {@code
     * delivery-runs.invalid-economics} for a number out of range; 412 {@code delivery-runs.version-mismatch} when
     * the settings changed meanwhile. A save that changes nothing makes no new version.
     */
    EconomicsRevision replace(EconomicsSettingsCommand command, Long expectedVersion);

    /** The audit trail, newest first. */
    List<EconomicsChange> changes(int limit);
}
