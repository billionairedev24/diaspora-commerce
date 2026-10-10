package com.afrotransact.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.afrotransact.ai.domain.moderation.ImageDecision;
import com.afrotransact.ai.domain.moderation.ImageLabel;
import com.afrotransact.ai.domain.moderation.ImageModerationPolicy;
import org.junit.jupiter.api.Test;

class ImageModerationPolicyTest {

    @Test
    void cleanImageIsAllowed() {
        var v = ImageModerationPolicy.decide("allow", List.of(new ImageLabel("non-white background", "grey")));
        assertThat(v.decision()).isEqualTo(ImageDecision.ALLOW);
        assertThat(v.labels()).extracting(ImageLabel::code).containsExactly("NON_WHITE_BACKGROUND");
    }

    @Test
    void labelsImplyAMinimumDecisionWhateverTheModelSaid() {
        assertThat(ImageModerationPolicy.decide("ALLOW", List.of(new ImageLabel("WEAPONS", "a rifle"))).decision())
                .isEqualTo(ImageDecision.BLOCK);
        assertThat(ImageModerationPolicy.decide("ALLOW", List.of(new ImageLabel("watermark", "shop logo"))).decision())
                .isEqualTo(ImageDecision.REVIEW);
        assertThat(ImageModerationPolicy.decide("BLOCK", List.of(new ImageLabel("WATERMARK", ""))).decision())
                .isEqualTo(ImageDecision.BLOCK);
    }

    @Test
    void unknownLabelsAreInformationalAndDuplicatesCollapse() {
        var v = ImageModerationPolicy.decide("ALLOW", List.of(new ImageLabel("pretty-photo", ""),
                new ImageLabel("Pretty Photo", ""), new ImageLabel(" ", "")));
        assertThat(v.decision()).isEqualTo(ImageDecision.ALLOW);
        assertThat(v.labels()).extracting(ImageLabel::code).containsExactly("OTHER");
    }

    @Test
    void unreadableOrUnknownAnswersGoToReviewNeverAllow() {
        var unreadable = ImageModerationPolicy.decide(null, List.of());
        assertThat(unreadable.decision()).isEqualTo(ImageDecision.REVIEW);
        assertThat(unreadable.labels()).extracting(ImageLabel::code).containsExactly(ImageModerationPolicy.UNREADABLE);
        assertThat(ImageModerationPolicy.decide("probably fine", List.of()).decision()).isEqualTo(ImageDecision.REVIEW);
        assertThat(ImageModerationPolicy.decide("", List.of()).decision()).isEqualTo(ImageDecision.REVIEW);
    }
}
