package com.afrotransact.content.port;

import java.util.List;
import java.util.Optional;

import com.afrotransact.content.domain.help.HelpArticle;

/** Help center pages. */
public interface HelpArticleRepository {

    /** Every article (drafts included) by topic order, sort order, then title. */
    List<HelpArticle> findAll();

    Optional<HelpArticle> find(String slug);

    void insert(HelpArticle article);

    /** Updates when the stored version equals {@code expectedVersion}; false when someone saved in between. */
    boolean update(HelpArticle article, long expectedVersion);
}
