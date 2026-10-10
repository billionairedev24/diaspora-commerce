package com.afrotransact.ai.domain.guardrail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.afrotransact.ai.domain.lola.ProductCard;

/** Sentence-level grounding: a sentence survives only if every ATIN and price in it is backed by a tool result. */
public class DefaultGroundingVerifier implements GroundingVerifier {

    /** ATIN: {@code AT} + 8 upper-case base-36 characters. */
    public static final Pattern ATIN = Pattern.compile("(?<![A-Z0-9])AT[0-9A-Z]{8}(?![A-Z0-9])");

    private static final Pattern PREFIXED_MONEY = Pattern.compile(
            "(?:US\\$|C\\$|A\\$|\\$|£|€|₦|₵|\\b(?:USD|GBP|EUR|NGN|GHS|CAD|KES)\\s?)\\s?"
                    + "(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?(?!\\d)");
    private static final Pattern SUFFIXED_MONEY = Pattern.compile(
            "(?<![\\d.,$£€₦₵])(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?\\s?(?:dollars|bucks|USD|GBP|EUR|NGN)\\b",
            Pattern.CASE_INSENSITIVE);

    @Override
    public GroundingResult verify(String modelText, GroundingFacts facts) {
        String text = modelText == null ? "" : modelText;
        StringBuilder kept = new StringBuilder(text.length());
        Map<String, ProductCard> cards = new LinkedHashMap<>();
        List<GroundingResult.DroppedClaim> dropped = new ArrayList<>();
        for (String segment : segments(text)) {
            List<GroundingResult.DroppedClaim> problems = new ArrayList<>();
            List<String> atins = new ArrayList<>();
            Matcher a = ATIN.matcher(segment);
            while (a.find()) {
                String atin = a.group();
                if (facts.knows(atin)) {
                    atins.add(atin);
                } else {
                    problems.add(new GroundingResult.DroppedClaim("ATIN", atin));
                }
            }
            checkMoney(PREFIXED_MONEY, segment, facts, problems);
            checkMoney(SUFFIXED_MONEY, segment, facts, problems);
            if (problems.isEmpty()) {
                kept.append(segment);
                atins.forEach(atin -> facts.product(atin).ifPresent(card -> cards.putIfAbsent(atin, card)));
            } else {
                dropped.addAll(problems);
                kept.append(lineBreakOf(segment));
            }
        }
        return new GroundingResult(kept.toString(), List.copyOf(cards.values()), dropped);
    }

    @Override
    public int completeSentencesEnd(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '\n') {
                return i + 1;
            }
            if ((c == '.' || c == '!' || c == '?') && i + 1 < text.length()
                    && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Splits into sentences; each segment keeps its leading whitespace so concatenation restores the text. */
    static List<String> segments(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean boundary = c == '\n' || ((c == '.' || c == '!' || c == '?')
                    && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1))));
            if (boundary) {
                out.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    private static void checkMoney(Pattern pattern, String segment, GroundingFacts facts,
                                   List<GroundingResult.DroppedClaim> problems) {
        Matcher m = pattern.matcher(segment);
        while (m.find()) {
            long whole = Long.parseLong(m.group(1).replace(",", ""));
            String cents = m.group(2);
            long minor = whole * 100 + (cents == null ? 0 : cents.length() == 1
                    ? Long.parseLong(cents) * 10 : Long.parseLong(cents));
            if (!facts.allowsAmount(minor)) {
                problems.add(new GroundingResult.DroppedClaim("PRICE", m.group().trim()));
            }
        }
    }

    /** A dropped sentence leaves only its line break behind, so lists keep their shape. */
    private static String lineBreakOf(String segment) {
        return segment.endsWith("\n") ? "\n" : "";
    }
}
