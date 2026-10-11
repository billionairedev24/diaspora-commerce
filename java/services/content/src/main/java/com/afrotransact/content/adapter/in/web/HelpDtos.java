package com.afrotransact.content.adapter.in.web;

import java.time.Instant;
import java.util.List;

import com.afrotransact.content.application.HelpCenterService.ArticlePage;
import com.afrotransact.content.application.HelpCenterService.TopicCount;
import com.afrotransact.content.domain.help.HelpArticle;
import com.afrotransact.content.domain.help.HelpArticleStatus;
import com.afrotransact.content.domain.help.HelpSection;
import com.afrotransact.content.domain.help.HelpTopic;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Help center bodies. */
final class HelpDtos {

    private HelpDtos() {
    }

    @Schema(name = "HelpSection")
    record SectionDto(String heading, @Schema(description = "Markdown") String body) {

        static SectionDto of(HelpSection s) {
            return new SectionDto(s.heading(), s.body());
        }

        HelpSection toDomain() {
            return new HelpSection(heading, body);
        }
    }

    @Schema(name = "HelpTopicCount")
    record TopicDto(HelpTopic key, String title, int count) {

        static TopicDto of(TopicCount t) {
            return new TopicDto(t.topic(), t.topic().title(), t.count());
        }
    }

    @Schema(name = "HelpArticleSummary")
    record SummaryDto(String slug, HelpTopic topic, String topicTitle, String title, String summary, Instant updatedAt) {

        static SummaryDto of(HelpArticle a) {
            return new SummaryDto(a.slug(), a.topic(), a.topic().title(), a.title(), a.summary(), a.updatedAt());
        }
    }

    @Schema(name = "HelpArticlePage")
    record PageDto(List<SummaryDto> items, List<TopicDto> topics,
                   @Schema(description = "Cursor of the next page; absent on the last page") String nextCursor) {

        static PageDto of(ArticlePage p) {
            return new PageDto(p.items().stream().map(SummaryDto::of).toList(),
                    p.topics().stream().map(TopicDto::of).toList(), p.nextCursor());
        }
    }

    @Schema(name = "HelpArticle")
    record ArticleDto(String slug, HelpTopic topic, String topicTitle, String title, String summary,
                      List<SectionDto> sections, HelpArticleStatus status, int sortOrder, Instant updatedAt,
                      long version) {

        static ArticleDto of(HelpArticle a) {
            return new ArticleDto(a.slug(), a.topic(), a.topic().title(), a.title(), a.summary(),
                    a.sections().stream().map(SectionDto::of).toList(), a.status(), a.sortOrder(), a.updatedAt(),
                    a.version());
        }
    }

    @Schema(name = "HelpArticleRequest")
    record ArticleRequest(@NotNull HelpTopic topic, @NotBlank String title, @NotBlank String summary,
                          @NotNull List<SectionDto> sections, @NotNull HelpArticleStatus status, Integer sortOrder,
                          @Schema(description = "The version you edited; omit to create a new article")
                          Long expectedVersion) {

        HelpArticle toDomain(String slug) {
            return new HelpArticle(slug, topic, title, summary, sections.stream().map(SectionDto::toDomain).toList(),
                    status, sortOrder == null ? 0 : sortOrder, null, 0);
        }
    }
}
