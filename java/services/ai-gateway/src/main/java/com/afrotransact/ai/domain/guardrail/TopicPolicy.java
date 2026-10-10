package com.afrotransact.ai.domain.guardrail;

/** Topic limits for Lola: shopping, orders, services and recipes are in scope; medical and legal advice are not. */
public interface TopicPolicy {

    TopicVerdict assess(String shopperMessage);
}
