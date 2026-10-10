package com.afrotransact.ai.port;

/**
 * Hook that reviews model output before it reaches the user. The default adapter is rule based; a provider
 * moderation endpoint can replace it without touching the agent.
 */
public interface OutputModerator {

    Verdict review(String text);

    /**
     * Review result.
     *
     * @param allowed     false when the text must not be shown
     * @param replacement text to show instead (empty when allowed)
     * @param category    why it was blocked
     */
    record Verdict(boolean allowed, String replacement, String category) {

        public static final Verdict ALLOW = new Verdict(true, "", "none");
    }
}
