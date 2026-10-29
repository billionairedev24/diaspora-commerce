package com.afrotransact.content.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import com.afrotransact.content.domain.help.HelpArticle;
import com.afrotransact.content.domain.help.HelpArticleStatus;
import com.afrotransact.content.domain.help.HelpDraft;
import com.afrotransact.content.domain.help.HelpDraft.Edit;
import com.afrotransact.content.domain.help.HelpRevision;
import com.afrotransact.content.domain.help.HelpSection;
import com.afrotransact.content.domain.help.HelpTopic;
import org.junit.jupiter.api.Test;

class HelpDraftTest {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");
    private static final HelpArticle LIVE = new HelpArticle("shipping", HelpTopic.ORDERS_DELIVERY, "Shipping", "Carriers",
            List.of(new HelpSection("Carrier shipping", "USPS, UPS and FedEx.")), HelpArticleStatus.PUBLISHED, 10, NOW, 3, "maya");

    @Test
    void aDraftStartsFromTheLivePageAndRemembersItsVersion() {
        HelpDraft d = HelpDraft.startFrom(LIVE, "sam", NOW);
        assertThat(d.baseVersion()).isEqualTo(3);
        assertThat(d.sections()).isEqualTo(LIVE.sections());
        assertThat(d.rowVersion()).isZero();
    }

    @Test
    void savingValidatesThePageAndRecordsTheEditor() {
        HelpDraft d = HelpDraft.startFrom(LIVE, "sam", NOW).edited(new Edit(HelpTopic.ORDERS_DELIVERY, "Shipping & delivery", "Carriers and pickup",
                List.of(new HelpSection("Pickup", "Collect at a locker.")), 5, " Adds pickup "), "kai", NOW);
        assertThat(d.updatedBy()).isEqualTo("kai");
        assertThat(d.createdBy()).isEqualTo("sam");
        assertThat(d.rowVersion()).isEqualTo(1);
        assertThat(d.changeSummary()).isEqualTo("Adds pickup");
        assertThatThrownBy(() -> d.edited(new Edit(HelpTopic.ORDERS_DELIVERY, "", "x", List.of(), 0, null), "kai", NOW))
                .isInstanceOf(ContentRuleViolation.class);
    }

    @Test
    void aRevisionRestoresUnderTheNextVersionKeepingVisibility() {
        HelpRevision old = HelpRevision.of(LIVE, 1, "Initial content", "Initial load", null, NOW);
        HelpArticle restored = old.asArticle(HelpArticleStatus.DRAFT, 4, "kai", NOW);
        assertThat(restored.version()).isEqualTo(4);
        assertThat(restored.status()).isEqualTo(HelpArticleStatus.DRAFT);
        assertThat(restored.updatedBy()).isEqualTo("kai");
        assertThat(restored.sections()).isEqualTo(LIVE.sections());
    }
}
