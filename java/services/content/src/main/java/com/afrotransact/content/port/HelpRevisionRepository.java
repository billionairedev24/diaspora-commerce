package com.afrotransact.content.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.afrotransact.content.domain.help.HelpRevision;

/** Every published state of each help page. */
public interface HelpRevisionRepository {

    /** The page's revisions, newest first. */
    List<HelpRevision> list(String slug);

    Optional<HelpRevision> find(String slug, int revision);

    /** The number the next revision of the page gets. */
    int nextRevision(String slug);

    /** The newest revision number of every page. */
    Map<String, Integer> latestRevisions();

    void append(HelpRevision revision);
}
