package com.afrotransact.content.domain.help;

import java.time.Instant;
import java.util.List;

/**
 * One published state of a help page, kept for the history, the diff and the restore.
 *
 * @param slug          page
 * @param revision      1, 2, 3… per page
 * @param topic         topic
 * @param title         title
 * @param summary       summary
 * @param sections      sections
 * @param status        PUBLISHED or DRAFT (a hidden page)
 * @param sortOrder     order within the topic
 * @param changeSummary what changed
 * @param reason        why it was published
 * @param publishedBy   who published it (null for the initial load)
 * @param publishedAt   when
 */
public record HelpRevision(String slug, int revision, HelpTopic topic, String title, String summary,
                           List<HelpSection> sections, HelpArticleStatus status, int sortOrder, String changeSummary,
                           String reason, String publishedBy, Instant publishedAt) {

    public HelpRevision {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /** The revision that records {@code article} as it was just saved. */
    public static HelpRevision of(HelpArticle article, int revision, String changeSummary, String reason, String by,
                                  Instant at) {
        return new HelpRevision(article.slug(), revision, article.topic(), article.title(), article.summary(),
                article.sections(), article.status(), article.sortOrder(), changeSummary, reason, by, at);
    }

    /** The page this revision restores to, keeping the page's current visibility. */
    public HelpArticle asArticle(HelpArticleStatus currentStatus, long version, String by, Instant at) {
        return new HelpArticle(slug, topic, title, summary, sections, currentStatus, sortOrder, at, version, by);
    }
}
