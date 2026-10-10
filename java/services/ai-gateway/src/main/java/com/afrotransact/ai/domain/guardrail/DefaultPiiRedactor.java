package com.afrotransact.ai.domain.guardrail;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex + checksum redactor. Order matters: emails first (they contain digits), then cards (Luhn-valid 13–19 digits,
 * spaces or dashes allowed), SSNs, phone numbers (NANP and international) and US-style street addresses.
 * The same value always gets the same placeholder within one conversation turn.
 */
public class DefaultPiiRedactor implements PiiRedactor {

    private static final Pattern EMAIL = Pattern.compile(
            "(?<![\\w.+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern CARD = Pattern.compile("(?<![\\d-])(?:\\d[ -]?){12,18}\\d(?![\\d-])");
    private static final Pattern SSN = Pattern.compile("(?<![\\d-])(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}(?![\\d-])");
    private static final Pattern PHONE = Pattern.compile(
            "(?<![\\w+-])(?:\\+\\d{1,3}[\\s.-]?\\(?\\d{1,4}\\)?(?:[\\s.-]?\\d{2,4}){2,4}"
                    + "|(?:1[\\s.-])?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4})(?![\\w-])");
    private static final Pattern ADDRESS = Pattern.compile(
            "\\b\\d{1,6}[A-Za-z]?\\s+"
                    + "(?:(?:[A-Za-z0-9][A-Za-z0-9.'-]*\\s+){1,4}"
                    + "(?i:street|avenue|road|boulevard|lane|drive|court|terrace|parkway|highway|crescent|close)\\b"
                    + "|(?:[A-Z0-9][A-Za-z0-9.'-]*\\s+){1,4}(?:St|Ave|Rd|Blvd|Ln|Dr|Ct|Way|Pl|Ter|Cir|Pkwy|Hwy)\\b\\.?)"
                    + "(?:,?\\s+(?i:apt|apartment|suite|ste|unit|#)\\.?\\s*[A-Za-z0-9-]+)?");

    private final List<Rule> rules = List.of(
            new Rule(PiiType.EMAIL, EMAIL, s -> true),
            new Rule(PiiType.CARD, CARD, s -> Luhn.isValidCardNumber(digits(s))),
            new Rule(PiiType.SSN, SSN, s -> true),
            new Rule(PiiType.PHONE, PHONE, s -> digits(s).length() >= 10 && digits(s).length() <= 15),
            new Rule(PiiType.ADDRESS, ADDRESS, s -> true));

    @Override
    public Redaction redact(String text) {
        return redact(text, Redaction.none(""));
    }

    @Override
    public Redaction redact(String text, Redaction previous) {
        if (text == null || text.isEmpty()) {
            return new Redaction(text == null ? "" : text, previous.placeholders(), previous.counts());
        }
        Map<String, String> placeholderOf = new LinkedHashMap<>();
        Map<String, String> originals = new LinkedHashMap<>(previous.placeholders());
        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);
        counts.putAll(previous.counts());
        originals.forEach((placeholder, original) -> placeholderOf.put(original, placeholder));

        String current = text;
        for (Rule rule : rules) {
            Matcher m = rule.pattern().matcher(current);
            StringBuilder out = new StringBuilder();
            int last = 0;
            while (m.find()) {
                String value = m.group();
                if (!rule.valid().test(value)) {
                    continue;
                }
                String placeholder = placeholderOf.get(value);
                if (placeholder == null) {
                    int n = counts.merge(rule.type(), 1, Integer::sum);
                    placeholder = "[" + rule.type().name() + "_" + n + "]";
                    placeholderOf.put(value, placeholder);
                    originals.put(placeholder, value);
                }
                out.append(current, last, m.start()).append(placeholder);
                last = m.end();
            }
            out.append(current, last, current.length());
            current = out.toString();
        }
        return new Redaction(current, originals, counts);
    }

    private static String digits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                b.append(c);
            }
        }
        return b.toString();
    }

    private record Rule(PiiType type, Pattern pattern, Predicate<String> valid) {
    }
}
