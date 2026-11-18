package com.afrotransact.catalog.domain.brand;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.afrotransact.catalog.domain.CatalogRuleViolation;

/**
 * What a seller may type as a new brand. The registry is shown to shoppers, so a proposed name is held to a narrow
 * shape before it is stored: letters, digits and the punctuation real brands use, no links, no e-mail addresses, no
 * profanity, and something to compare on. Staff-listed brands go through {@link BrandNames#clean} only.
 */
public final class BrandProposalRules {

    private static final Pattern ALLOWED = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N} &'’.,+\\-()!/®™]*");
    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");
    private static final Pattern LINK = Pattern.compile("(?i)(://|\\bwww\\.|\\b[\\p{L}\\p{N}-]+\\.(com|net|org|io|co|biz|info|shop|"
            + "store|xyz|app|ly|me|ng|uk|us|fr|es|de|ru|cn|site|online|link|click)\\b)");
    private static final Pattern REPEATS = Pattern.compile("(.)\\1{4,}");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern WORD_BREAK = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final String FIELD = "attributes.brand";

    /**
     * A short seed of words no brand name should contain (English, French, Spanish), matched as whole words. It stops
     * the obvious; the review queue catches the rest.
     */
    private static final Set<String> BLOCKED_WORDS = Set.of("fuck", "fucking", "shit", "bitch", "cunt", "pussy",
            "asshole", "whore", "slut", "nigger", "nigga", "faggot", "retard", "porn", "porno", "xxx", "nazi", "merde",
            "putain", "salope", "connard", "encule", "pute", "mierda", "puta", "pendejo", "cabron", "joder", "scam");

    private BrandProposalRules() {
    }

    /**
     * The display name of a proposed brand: trimmed and with inner spaces collapsed.
     *
     * @throws CatalogRuleViolation naming the rule it breaks, in words the seller can act on
     */
    public static String check(String name) {
        String clean = BrandNames.clean(name, FIELD);
        if (clean.indexOf('@') >= 0) {
            throw refuse("Brand names cannot contain an e-mail address", "No e-mail addresses");
        }
        if (LINK.matcher(clean).find()) {
            throw refuse("Brand names cannot contain a web address", "No web addresses");
        }
        if (!ALLOWED.matcher(clean).matches()) {
            throw refuse("Brand names use letters, digits and & ' . , - + ( ) ! /",
                    "Letters, digits and & ' . , - + ( ) ! / only, starting with a letter or digit");
        }
        if (!HAS_LETTER.matcher(clean).find()) {
            throw refuse("A brand name needs letters", "Add at least one letter");
        }
        if (REPEATS.matcher(clean).find()) {
            throw refuse("That does not look like a brand name", "Check the spelling");
        }
        String folded = MARKS.matcher(Normalizer.normalize(clean, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
        for (String word : WORD_BREAK.split(folded)) {
            if (BLOCKED_WORDS.contains(word)) {
                throw refuse("That name cannot be used as a brand", "Choose another name");
            }
        }
        return clean;
    }

    private static CatalogRuleViolation refuse(String message, String field) {
        return new CatalogRuleViolation(message, Map.of(FIELD, field));
    }
}
