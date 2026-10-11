package com.afrotransact.content.application;

import java.util.List;

import com.afrotransact.content.domain.help.HelpArticle;
import com.afrotransact.content.domain.help.HelpTopic;

/** The help center: published pages for shoppers, every page for site-content staff. */
public interface HelpCenterService {

    /** Published articles (not the hub) matching the topic and query, a page at a time. */
    ArticlePage published(HelpTopic topic, String query, String cursor, int limit);

    /** A published article; the hub is {@link HelpArticle#HUB}. */
    HelpArticle publishedArticle(String slug);

    /** Every article including drafts (content:site.view). */
    List<HelpArticle> all();

    /** Creates or replaces an article (content:site.edit); {@code expectedVersion} is null for a new one. */
    HelpArticle save(HelpArticle article, Long expectedVersion);

    /**
     * One page of articles.
     *
     * @param items      the articles on this page
     * @param topics     topics with at least one published article, and how many
     * @param nextCursor opaque cursor of the next page, null on the last one
     */
    record ArticlePage(List<HelpArticle> items, List<TopicCount> topics, String nextCursor) {
    }

    /**
     * A topic and its number of published articles.
     *
     * @param topic topic
     * @param count published articles
     */
    record TopicCount(HelpTopic topic, int count) {
    }
}
