package com.afrotransact.ai.domain.guardrail;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pattern-based prompt-injection filter for seller- and provider-supplied text. */
public class DefaultInjectionFilter implements InjectionFilter {

    private static final String REMOVED = "[removed]";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("\\b(?:ignore|disregard|forget|override|bypass)\\b[^.\\n]{0,40}?"
                    + "\\b(?:previous|prior|above|earlier|preceding|all|any|system|your|the)\\b[^.\\n]{0,30}?"
                    + "\\b(?:instructions?|prompts?|messages?|rules|directions|guidelines|context)\\b", FLAGS),
            Pattern.compile("\\byou\\s+are\\s+now\\b[^.\\n]{0,80}", FLAGS),
            Pattern.compile("\\b(?:act|behave|respond)\\s+as\\s+(?:if\\s+you\\s+were\\s+|an?\\s+|the\\s+)?"
                    + "(?:system|admin|administrator|developer|assistant|ai|model|dan)\\b[^.\\n]{0,60}", FLAGS),
            Pattern.compile("\\bpretend\\s+(?:to\\s+be|you\\s+are)\\b[^.\\n]{0,60}", FLAGS),
            Pattern.compile("\\b(?:new|updated|real|actual)\\s+(?:system\\s+)?instructions?\\s*:?", FLAGS),
            Pattern.compile("\\b(?:reveal|print|show|repeat|output)\\s+(?:me\\s+)?(?:your|the)\\s+"
                    + "(?:system\\s+)?(?:prompt|instructions|rules)\\b", FLAGS),
            Pattern.compile("(?m)^\\s*(?:system|assistant|developer|user|tool)\\s*:", FLAGS),
            Pattern.compile("<\\|?\\s*/?\\s*(?:im_start|im_end|system|endoftext|assistant|user)\\s*\\|?>", FLAGS),
            Pattern.compile("\\[/?(?:INST|SYS)\\]|<<\\s*/?SYS\\s*>>", FLAGS),
            Pattern.compile("#{2,}\\s*(?:instruction|system|prompt)s?\\b", FLAGS),
            Pattern.compile("</?\\s*untrusted[^>]*>", FLAGS),
            Pattern.compile("\\b(?:call|use|invoke|run|execute)\\s+(?:the\\s+)?[a-z]+(?:_[a-z]+)+\\s*(?:tool|function)?\\b",
                    FLAGS),
            Pattern.compile("\\b(?:add|put)\\s+[^.\\n]{0,40}\\bto\\s+(?:the|their|your|my)\\s+(?:cart|basket)\\b",
                    FLAGS),
            Pattern.compile("\\b(?:do\\s+not|don'?t|never)\\s+(?:tell|inform|show)\\s+the\\s+(?:user|customer|shopper)\\b",
                    FLAGS),
            Pattern.compile("\\b(?:checkout|pay|purchase|buy)\\s+(?:now\\s+)?without\\s+(?:asking|confirmation)\\b",
                    FLAGS));

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    @Override
    public FilteredText sanitize(String untrusted, int maxLength) {
        if (untrusted == null || untrusted.isEmpty()) {
            return new FilteredText("", 0);
        }
        String text = CONTROL.matcher(untrusted).replaceAll("");
        int removed = 0;
        for (Pattern p : PATTERNS) {
            Matcher m = p.matcher(text);
            StringBuilder out = new StringBuilder();
            int last = 0;
            while (m.find()) {
                out.append(text, last, m.start()).append(REMOVED);
                last = m.end();
                removed++;
            }
            out.append(text, last, text.length());
            text = out.toString();
        }
        if (maxLength > 0 && text.length() > maxLength) {
            text = text.substring(0, maxLength) + "…";
        }
        return new FilteredText(text, removed);
    }

    @Override
    public String quote(String source, String sanitizedContent) {
        String safeSource = source == null ? "unknown" : source.replaceAll("[^a-z0-9_:-]", "");
        String body = sanitizedContent == null ? "" : sanitizedContent.replace("<", "‹").replace(">", "›");
        return "<untrusted source=\"" + safeSource + "\">\n" + body + "\n</untrusted>";
    }
}
