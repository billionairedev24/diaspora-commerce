package com.afrotransact.content.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.afrotransact.content.domain.ContentRuleViolation;
import com.afrotransact.content.domain.help.HelpArticle;
import com.afrotransact.content.domain.help.HelpTopic;
import com.afrotransact.content.port.HelpArticleRepository;
import com.afrotransact.platform.security.Actor;
import com.afrotransact.platform.web.error.ConflictException;
import com.afrotransact.platform.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class HelpCenterServiceImpl implements HelpCenterService {

    static final int MAX_LIMIT = 50;

    private final HelpArticleRepository articles;
    private final ContentAccess access;
    private final AuditTrail audit;
    private final Clock clock;

    HelpCenterServiceImpl(HelpArticleRepository articles, ContentAccess access, AuditTrail audit, Clock clock) {
        this.articles = articles;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ArticlePage published(HelpTopic topic, String query, String cursor, int limit) {
        int size = Math.clamp(limit <= 0 ? 20 : limit, 1, MAX_LIMIT);
        int offset = decodeCursor(cursor);
        List<HelpArticle> listed = articles.findAll().stream()
                .filter(HelpArticle::published).filter(a -> !HelpArticle.HUB.equals(a.slug())).toList();
        Map<HelpTopic, Integer> counts = new EnumMap<>(HelpTopic.class);
        listed.forEach(a -> counts.merge(a.topic(), 1, Integer::sum));
        List<HelpArticle> matching = listed.stream()
                .filter(a -> topic == null || a.topic() == topic).filter(a -> a.matches(query)).toList();
        int end = Math.min(matching.size(), offset + size);
        List<HelpArticle> page = offset >= matching.size() ? List.of() : matching.subList(offset, end);
        String next = end < matching.size() ? encodeCursor(end) : null;
        List<TopicCount> topics = counts.entrySet().stream().map(e -> new TopicCount(e.getKey(), e.getValue())).toList();
        return new ArticlePage(page, topics, next);
    }

    @Override
    @Transactional(readOnly = true)
    public HelpArticle publishedArticle(String slug) {
        return articles.find(slug).filter(HelpArticle::published).orElseThrow(() -> notFound(slug));
    }

    @Override
    @Transactional(readOnly = true)
    public List<HelpArticle> all() {
        access.requireAny(ContentPermissions.SITE_VIEW, ContentPermissions.SITE_EDIT);
        return articles.findAll();
    }

    @Override
    @Transactional
    public HelpArticle save(HelpArticle article, Long expectedVersion) {
        Actor actor = access.require(ContentPermissions.SITE_EDIT);
        Instant now = Instant.now(clock);
        var current = articles.find(article.slug());
        if (current.isEmpty()) {
            HelpArticle created = new HelpArticle(article.slug(), article.topic(), article.title(), article.summary(),
                    article.sections(), article.status(), article.sortOrder(), now, 0);
            created.validate();
            articles.insert(created);
            audit.record(actor.subject(), "help-article.created", "help-article", created.slug(),
                    Map.of("status", created.status().name()));
            return created;
        }
        if (expectedVersion == null || expectedVersion != current.get().version()) {
            throw new ConflictException("content.stale-help-article",
                    "Someone changed this article in the meantime. Reload it and apply your change again.");
        }
        HelpArticle next = new HelpArticle(article.slug(), article.topic(), article.title(), article.summary(),
                article.sections(), article.status(), article.sortOrder(), now, current.get().version() + 1);
        next.validate();
        if (!articles.update(next, expectedVersion)) {
            throw new ConflictException("content.stale-help-article",
                    "Someone changed this article in the meantime. Reload it and apply your change again.");
        }
        audit.record(actor.subject(), "help-article.updated", "help-article", next.slug(),
                Map.of("from", current.get().status().name(), "to", next.status().name()));
        return next;
    }

    static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("o:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (raw.startsWith("o:")) {
                int offset = Integer.parseInt(raw.substring(2));
                if (offset >= 0) {
                    return offset;
                }
            }
        } catch (IllegalArgumentException e) {
            // falls through to the rule violation below
        }
        throw new ContentRuleViolation("content.invalid-cursor", "The cursor is invalid", Map.of("cursor", "Invalid"));
    }

    private static NotFoundException notFound(String slug) {
        return new NotFoundException("content.help-article-not-found", "No help article " + slug);
    }
}
