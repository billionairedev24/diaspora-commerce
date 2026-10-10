package com.afrotransact.ai.domain.guardrail;

import java.util.regex.Pattern;

/** Keyword rules evaluated before the model is called (cheap, deterministic and auditable). */
public class DefaultTopicPolicy implements TopicPolicy {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern SELF_HARM = Pattern.compile(
            "\\b(?:kill\\s+myself|suicid\\w*|self[-\\s]?harm|end\\s+my\\s+life|hurt\\s+myself)\\b", FLAGS);
    private static final Pattern UNSAFE = Pattern.compile(
            "\\b(?:make|build|buy)\\s+(?:a\\s+)?(?:bomb|explosive|weapon|gun)s?\\b"
                    + "|\\b(?:poison\\s+(?:someone|somebody|my|him|her))\\b", FLAGS);
    private static final Pattern HUMAN = Pattern.compile(
            "\\b(?:talk|speak|chat)\\s+(?:to|with)\\s+(?:a\\s+|an\\s+|the\\s+)?"
                    + "(?:human|person|agent|someone|somebody|representative|rep|support|customer\\s+service)\\b"
                    + "|\\breal\\s+(?:person|human)\\b|\\bhuman\\s+agent\\b|\\bcontact\\s+support\\b", FLAGS);
    private static final Pattern MEDICAL = Pattern.compile(
            "\\b(?:diagnos\\w*|dosage|how\\s+much\\s+should\\s+i\\s+take|prescri\\w*|side\\s+effects?"
                    + "|cure\\s+(?:my|for)|treat\\s+(?:my|a|the)\\s+\\w+|symptoms?\\s+of|is\\s+it\\s+safe\\s+to\\s+take"
                    + "|interact\\w*\\s+with\\s+(?:my\\s+)?medication|medical\\s+advice)\\b", FLAGS);
    private static final Pattern LEGAL = Pattern.compile(
            "\\b(?:legal\\s+advice|lawsuit|sue\\s+(?:them|him|her|the|my)|lawyer|attorney|immigration\\s+(?:law|status|case)"
                    + "|visa\\s+(?:application|status|question)|is\\s+it\\s+legal\\s+to|court\\s+case|custody)\\b", FLAGS);

    @Override
    public TopicVerdict assess(String shopperMessage) {
        String text = shopperMessage == null ? "" : shopperMessage;
        if (SELF_HARM.matcher(text).find()) {
            return new TopicVerdict(TopicVerdict.Category.UNSAFE,
                    "I'm really sorry you're feeling this way. You deserve support right now: in the US you can call or "
                            + "text 988 (Suicide & Crisis Lifeline), any time. I'm also connecting you with a member "
                            + "of our team.");
        }
        if (UNSAFE.matcher(text).find()) {
            return new TopicVerdict(TopicVerdict.Category.UNSAFE,
                    "I can't help with that. I'm here for groceries, gifts, recipes, orders and booking services.");
        }
        if (HUMAN.matcher(text).find()) {
            return new TopicVerdict(TopicVerdict.Category.HUMAN_REQUESTED,
                    "Of course — I'm passing you to our support team with a summary of our chat so you don't have to "
                            + "repeat yourself.");
        }
        if (MEDICAL.matcher(text).find()) {
            return new TopicVerdict(TopicVerdict.Category.MEDICAL,
                    "I can't give medical advice. A pharmacist or doctor is the right person for that. I'm happy to "
                            + "help you find products, check ingredients listed by sellers or track an order.");
        }
        if (LEGAL.matcher(text).find()) {
            return new TopicVerdict(TopicVerdict.Category.LEGAL,
                    "I can't give legal advice — please speak to a qualified lawyer. I can help with shopping, "
                            + "orders, returns and booking services.");
        }
        return TopicVerdict.ALLOWED;
    }
}
