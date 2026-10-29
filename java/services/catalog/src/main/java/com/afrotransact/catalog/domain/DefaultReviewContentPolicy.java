package com.afrotransact.catalog.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Screens review text for profanity, links, e-mail addresses and phone numbers. Matching is forgiving on purpose
 * (case, accents, look-alike digits and symbols, stretched letters) because a miss publishes the text, while a false
 * hit only sends the review to a human.
 */
public final class DefaultReviewContentPolicy implements ReviewContentPolicy {

    /** Roots matched anywhere in a word: "fuck" also finds "fucking" and "motherfucker", "shit" finds "bullshit". */
    private static final List<String> ROOTS = List.of("fuck", "shit", "bitch", "asshole");

    /** Words (English, French, Spanish) matched as the start of a word. */
    private static final List<String> STEMS = List.of("bastard", "cunt", "whore", "slut", "dickhead", "nigger",
            "nigga", "faggot", "retard", "wanker", "twat", "merde", "putain", "connard", "salop", "encul", "nique",
            "mierda", "pendejo", "cabron", "joder", "hijueputa");

    /** Short words matched whole, so they cannot hit longer innocent words. */
    private static final List<String> WORDS = List.of("puta", "putas", "puto", "putos", "dick", "dicks", "cock",
            "cocks", "piss", "arse");

    private static final Pattern LINK = Pattern.compile("(?i)(?:\\b(?:https?://|www\\.)\\S+)"
            + "|(?:\\b[a-z0-9][a-z0-9-]*\\.(?:com|net|org|io|co|ng|uk|us|ly|me|shop|store|app|biz|info|xyz|link|click"
            + "|site|online|fr|es|de|gh|ke|za)\\b)");
    private static final Pattern EMAIL = Pattern.compile("(?i)[\\w.%+-]+\\s?(?:@|\\(at\\)|\\[at\\])\\s?[\\w.-]+\\.[a-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<![\\d])\\+?\\d(?:[\\s().-]{0,2}\\d){7,}(?![\\d])");
    private static final Pattern NOT_LETTER = Pattern.compile("[^a-z]+");

    private final List<Pattern> roots = new ArrayList<>();
    private final List<Pattern> prefixes = new ArrayList<>();
    private final List<Pattern> whole = new ArrayList<>();

    /** The built-in lists only. */
    public DefaultReviewContentPolicy() {
        this(List.of());
    }

    /**
     * The built-in lists plus operator-supplied words (matched whole).
     *
     * @param extraWords more words to block; blank entries are ignored
     */
    public DefaultReviewContentPolicy(Collection<String> extraWords) {
        ROOTS.forEach(s -> roots.add(stretchable(s)));
        STEMS.forEach(s -> prefixes.add(stretchable(s)));
        WORDS.forEach(w -> whole.add(stretchable(w)));
        for (String w : extraWords) {
            if (w != null && !w.isBlank()) {
                whole.add(stretchable(w.strip()));
            }
        }
    }

    @Override
    public List<ReviewFlag> screen(String title, String text) {
        String joined = (title == null ? "" : title) + "\n" + (text == null ? "" : text);
        List<ReviewFlag> flags = new ArrayList<>();
        if (profane(joined)) {
            flags.add(ReviewFlag.PROFANITY);
        }
        if (EMAIL.matcher(joined).find()) {
            flags.add(ReviewFlag.EMAIL);
        }
        if (LINK.matcher(EMAIL.matcher(joined).replaceAll(" ")).find()) {
            flags.add(ReviewFlag.LINK);
        }
        if (PHONE.matcher(joined).find()) {
            flags.add(ReviewFlag.PHONE);
        }
        return List.copyOf(flags);
    }

    private boolean profane(String raw) {
        for (String token : NOT_LETTER.split(fold(raw))) {
            if (token.isEmpty()) {
                continue;
            }
            for (Pattern p : roots) {
                if (p.matcher(token).find()) {
                    return true;
                }
            }
            for (Pattern p : prefixes) {
                if (p.matcher(token).lookingAt()) {
                    return true;
                }
            }
            for (Pattern p : whole) {
                if (p.matcher(token).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "fuck" as a pattern that also accepts stretched letters: "fuuuck". */
    private static Pattern stretchable(String word) {
        StringBuilder regex = new StringBuilder();
        for (char c : fold(word).toCharArray()) {
            if (c >= 'a' && c <= 'z') {
                regex.append(c).append('+');
            }
        }
        return Pattern.compile(regex.toString());
    }

    /** Lower case, no accents, look-alike digits and symbols turned into letters. */
    private static String fold(String s) {
        String lower = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        StringBuilder out = new StringBuilder(lower.length());
        for (char c : lower.toCharArray()) {
            out.append(switch (c) {
                case '0' -> 'o';
                case '1' -> 'i';
                case '3' -> 'e';
                case '4', '@' -> 'a';
                case '5', '$' -> 's';
                case '7' -> 't';
                default -> c;
            });
        }
        return out.toString();
    }
}
