package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.afrotransact.content.domain.help.HelpArticle;
import com.afrotransact.content.domain.help.HelpArticleStatus;
import com.afrotransact.content.domain.help.HelpSection;
import com.afrotransact.content.domain.help.HelpTopic;
import org.junit.jupiter.api.Test;

class HelpArticleTest {

    private static HelpArticle article(String slug, List<HelpSection> sections) {
        return new HelpArticle(slug, HelpTopic.ORDERS_DELIVERY, "Shipping & delivery", "Carriers and pickup", sections,
                HelpArticleStatus.PUBLISHED, 0, null, 0);
    }

    @Test
    void aValidArticlePasses() {
        HelpArticle a = article("shipping", List.of(new HelpSection("Pickup", "Collect from a will-call counter.")));
        a.validate();
        assertThat(a.published()).isTrue();
    }

    @Test
    void slugSectionsAndFieldsAreChecked() {
        assertThatThrownBy(() -> article("Bad Slug", List.of(new HelpSection("", " "))).validate())
                .isInstanceOfSatisfying(ContentRuleViolation.class, e -> assertThat(e.fieldErrors())
                        .containsKeys("slug", "sections[0].heading", "sections[0].body"));
        assertThatThrownBy(() -> article("shipping", List.of()).validate())
                .isInstanceOfSatisfying(ContentRuleViolation.class, e -> assertThat(e.fieldErrors()).containsKey("sections"));
    }

    @Test
    void matchesTitleSummaryAndSectionText() {
        HelpArticle a = article("shipping", List.of(new HelpSection("Pickup", "Collect from a LOCKER.")));
        assertThat(a.matches(null)).isTrue();
        assertThat(a.matches("delivery")).isTrue();
        assertThat(a.matches("locker")).isTrue();
        assertThat(a.matches("refund")).isFalse();
    }
}
