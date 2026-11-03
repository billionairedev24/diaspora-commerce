package com.afrotransact.content.port;

import java.util.Optional;
import java.util.Set;

import com.afrotransact.content.domain.help.HelpDraft;

/** The working draft of each help page (at most one per page). */
public interface HelpDraftRepository {

    Optional<HelpDraft> find(String slug);

    /** Slugs that have a draft. */
    Set<String> slugsWithDraft();

    /** Stores a draft that does not exist yet; false when someone started one in the meantime. */
    boolean insert(HelpDraft draft);

    /** Replaces the draft when its stored row version equals {@code expectedRowVersion}. */
    boolean update(HelpDraft draft, long expectedRowVersion);

    void delete(String slug);
}
