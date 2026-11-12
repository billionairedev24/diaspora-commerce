package com.afrotransact.identity.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** What may be stored for a guided tour and which write wins when two devices disagree. */
public final class TourProgressRules {

    /** Most tours kept per person (about fifty exist; the limit only stops abuse). */
    public static final int MAX_TOURS = 200;
    /** Most records one merge may carry. */
    public static final int MAX_IMPORT = 100;
    /** Highest content version accepted. */
    public static final int MAX_VERSION = 1000;

    /** A tour or step id: lower-case words joined by "-" or "." (same shape as the data-tour ids in the web apps). */
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9]*(?:[-.][a-z0-9]+)*$");
    private static final int ID_MAX = 64;

    private TourProgressRules() {
    }

    /** Field errors for a record; empty when valid. */
    public static Map<String, String> validate(String tourId, int contentVersion, TourProgress.Status status,
                                               String lastStep) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!validId(tourId)) {
            errors.put("tourId", "Use lower-case words joined by - or . (at most 64 characters)");
        }
        if (contentVersion < 1 || contentVersion > MAX_VERSION) {
            errors.put("contentVersion", "Use a version from 1 to " + MAX_VERSION);
        }
        if (status == null) {
            errors.put("status", "Choose IN_PROGRESS, COMPLETED or DISMISSED");
        }
        if (lastStep != null && !validId(lastStep)) {
            errors.put("lastStep", "Use lower-case words joined by - or . (at most 64 characters)");
        }
        return errors;
    }

    public static boolean validId(String id) {
        return id != null && id.length() <= ID_MAX && ID.matcher(id).matches();
    }

    /**
     * Whether an incoming write replaces what is stored. A newer content version always wins and an older one never
     * does (an old browser cannot undo a newer tour's state). For the same version, a final status (completed or
     * dismissed) is not undone by "in progress", so replaying a finished tour keeps it finished; every other change applies.
     */
    public static boolean replaces(TourProgress stored, int incomingVersion, TourProgress.Status incomingStatus) {
        if (stored == null) {
            return true;
        }
        if (incomingVersion != stored.contentVersion()) {
            return incomingVersion > stored.contentVersion();
        }
        return !(stored.status().isFinal() && !incomingStatus.isFinal());
    }
}
