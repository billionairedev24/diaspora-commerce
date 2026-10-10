package com.afrotransact.ai.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.afrotransact.ai.domain.guardrail.DefaultTopicPolicy;
import com.afrotransact.ai.domain.guardrail.TopicVerdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TopicPolicyTest {

    private final DefaultTopicPolicy policy = new DefaultTopicPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"Jollof for 20", "Diwali dinner for 8", "Where is my order AT-1045?",
        "Find a braider near 77002", "Gift ideas for my mum", "Is shea butter good for dry skin?",
        "I want to return the damaged yam flour"})
    void shoppingIsAllowed(String message) {
        assertThat(policy.assess(message).allowed()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"What dosage of bitter leaf should I take for diabetes?", "Can you diagnose my rash?",
        "Does moringa interact with my medication?"})
    void medicalAdviceIsDeclined(String message) {
        TopicVerdict v = policy.assess(message);
        assertThat(v.category()).isEqualTo(TopicVerdict.Category.MEDICAL);
        assertThat(v.reply()).contains("medical advice");
    }

    @ParameterizedTest
    @ValueSource(strings = {"I need legal advice about my landlord", "Should I sue the seller?",
        "What is my immigration status if I travel?"})
    void legalAdviceIsDeclined(String message) {
        assertThat(policy.assess(message).category()).isEqualTo(TopicVerdict.Category.LEGAL);
    }

    @Test
    void askingForAPersonHandsOff() {
        assertThat(policy.assess("Can I talk to a real person please").category())
                .isEqualTo(TopicVerdict.Category.HUMAN_REQUESTED);
        assertThat(policy.assess("let me speak to customer service").category())
                .isEqualTo(TopicVerdict.Category.HUMAN_REQUESTED);
    }

    @Test
    void selfHarmGetsCrisisResources() {
        TopicVerdict v = policy.assess("I want to end my life");
        assertThat(v.category()).isEqualTo(TopicVerdict.Category.UNSAFE);
        assertThat(v.reply()).contains("988");
    }
}
