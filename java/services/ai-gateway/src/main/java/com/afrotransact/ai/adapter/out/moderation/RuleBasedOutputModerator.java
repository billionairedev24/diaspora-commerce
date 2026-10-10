package com.afrotransact.ai.adapter.out.moderation;

import java.util.List;
import java.util.regex.Pattern;

import com.afrotransact.ai.port.OutputModerator;
import org.springframework.stereotype.Component;

/**
 * Default output moderation: blocks model text that gives dosing or diagnosis, claims an action Lola cannot take
 * (placing orders, charging cards), or leaks instructions. Replace with a provider moderation endpoint by adding
 * another {@link OutputModerator} bean.
 */
@Component
class RuleBasedOutputModerator implements OutputModerator {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final List<Rule> RULES = List.of(
            new Rule("medical", Pattern.compile("\\b(?:take|use)\\s+\\d+\\s*(?:mg|milligrams?|ml|tablets?|pills?)\\b"
                    + "|\\byou\\s+(?:probably\\s+)?have\\s+(?:diabetes|hypertension|an?\\s+infection|cancer)\\b", FLAGS),
                    "I can't give medical advice — a pharmacist or doctor can help with that."),
            new Rule("unconfirmed_action", Pattern.compile("\\b(?:i(?:'ve| have)\\s+(?:placed|paid|charged|booked|"
                    + "ordered|purchased|checked\\s+out))\\b|\\byour\\s+card\\s+(?:has\\s+been|was)\\s+charged\\b", FLAGS),
                    "Nothing has been bought or booked yet — tap the button to confirm when you're ready."),
            new Rule("prompt_leak", Pattern.compile("\\b(?:my\\s+system\\s+prompt|my\\s+instructions\\s+(?:are|say))\\b",
                    FLAGS), "I'm here to help with shopping, orders and services."));

    @Override
    public Verdict review(String text) {
        if (text == null || text.isBlank()) {
            return Verdict.ALLOW;
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                return new Verdict(false, rule.replacement() + (text.endsWith("\n") ? "\n" : " "), rule.category());
            }
        }
        return Verdict.ALLOW;
    }

    private record Rule(String category, Pattern pattern, String replacement) {
    }
}
