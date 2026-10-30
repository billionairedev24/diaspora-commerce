package com.afrotransact.content.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.afrotransact.content.domain.help.HelpArticle;
import com.afrotransact.content.domain.help.HelpDraft;
import com.afrotransact.content.domain.help.HelpRevision;

/**
 * How staff change a help page without a developer: edit a working draft (nothing public changes), publish it with a
 * reason, look back through every published revision and restore one as the newest.
 */
public interface HelpAuthoringService {

    /** The working draft of a page, if any (content:site.view or edit). */
    Optional<HelpDraft> draft(String slug);

    /** Slugs that have a working draft (content:site.view or edit). */
    Set<String> slugsWithDraft();

    /** The newest revision number of every page (content:site.view or edit). */
    Map<String, Integer> latestRevisions();

    /** Saves the draft; the first save starts it from the live page. {@code expectedRowVersion} is null on the first. */
    HelpDraft save(String slug, HelpDraft.Edit edit, Long expectedRowVersion);

    void discard(String slug);

    /** Replaces the live text with the draft and records a revision; the page's visibility is unchanged. */
    HelpArticle publish(String slug, String reason, long expectedRowVersion);

    /** The page's revisions, newest first (content:site.view or edit). */
    List<HelpRevision> revisions(String slug);

    HelpRevision revision(String slug, int revision);

    /** Publishes an older revision's text as the newest revision (rollback). */
    HelpArticle restore(String slug, int revision, String reason);
}
