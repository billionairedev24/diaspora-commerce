package com.afrotransact.content.domain.help;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.ContentRuleViolation;

/**
 * The working copy of a published help page that staff edit before it replaces the live text. Saving it changes
 * nothing shoppers read. One draft per page.
 *
 * @param slug          page
 * @param topic         topic
 * @param title         title
 * @param summary       one line for the article list
 * @param sections      headings and Markdown bodies
 * @param sortOrder     order within the topic
 * @param changeSummary what changed
 * @param baseVersion   the page version the draft was started from (a newer one makes it stale)
 * @param rowVersion    optimistic-locking counter
 * @param createdBy     who started it
 * @param createdAt     when
 * @param updatedBy     who saved it last
 * @param updatedAt     when
 */
public record HelpDraft(String slug, HelpTopic topic, String title, String summary, List<HelpSection> sections,
                        int sortOrder, String changeSummary, long baseVersion, long rowVersion, String createdBy,
                        Instant createdAt, String updatedBy, Instant updatedAt) {

    public HelpDraft {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /**
     * What staff change in one save.
     *
     * @param topic         topic
     * @param title         title
     * @param summary       summary
     * @param sections      sections
     * @param sortOrder     order within the topic
     * @param changeSummary what changed
     */
    public record Edit(HelpTopic topic, String title, String summary, List<HelpSection> sections, int sortOrder,
                       String changeSummary) {
    }

    public static HelpDraft startFrom(HelpArticle live, String actor, Instant now) {
        return new HelpDraft(live.slug(), live.topic(), live.title(), live.summary(), live.sections(), live.sortOrder(),
                null, live.version(), 0, actor, now, actor, now);
    }

    /** The draft after a save: validated as a page, the editor recorded, the row version raised. */
    public HelpDraft edited(Edit edit, String actor, Instant now) {
        if (edit.changeSummary() != null && edit.changeSummary().length() > 1000) {
            throw new ContentRuleViolation("content.invalid-help-article", "The help article is invalid",
                    Map.of("changeSummary", "At most 1,000 characters"));
        }
        HelpDraft next = new HelpDraft(slug, edit.topic(), edit.title(), edit.summary(), edit.sections(),
                edit.sortOrder(), edit.changeSummary() == null || edit.changeSummary().isBlank() ? null
                        : edit.changeSummary().strip(), baseVersion, rowVersion + 1, createdBy, createdAt, actor, now);
        next.asArticle(HelpArticleStatus.PUBLISHED, baseVersion, null).validate();
        return next;
    }

    /** The page this draft becomes when published. */
    public HelpArticle asArticle(HelpArticleStatus status, long version, String by) {
        return new HelpArticle(slug, topic, title, summary, sections, status, sortOrder, updatedAt, version, by);
    }
}
