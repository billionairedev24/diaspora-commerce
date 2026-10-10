package com.afrotransact.identity.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 12+ characters, upper case, lower case, a digit, and none of the user's names or email local part. */
public class DefaultPasswordPolicy implements PasswordPolicy {

    /** Personal values shorter than this are too common to block (for example "Al" or "Bo"). */
    private static final int MIN_PERSONAL_LENGTH = 3;

    @Override
    public List<String> violations(String password, List<String> personal) {
        List<String> out = new ArrayList<>();
        String pw = password == null ? "" : password;
        if (pw.length() < MIN_LENGTH) {
            out.add("Use at least " + MIN_LENGTH + " characters");
        }
        if (pw.length() > MAX_LENGTH) {
            out.add("Use at most " + MAX_LENGTH + " characters");
        }
        if (pw.chars().noneMatch(Character::isUpperCase)) {
            out.add("Add an upper-case letter");
        }
        if (pw.chars().noneMatch(Character::isLowerCase)) {
            out.add("Add a lower-case letter");
        }
        if (pw.chars().noneMatch(Character::isDigit)) {
            out.add("Add a number");
        }
        String folded = fold(pw);
        for (String value : personal) {
            if (value == null) {
                continue;
            }
            for (String part : fold(value).split("[\\s'’\\-._+]+")) {
                if (part.length() >= MIN_PERSONAL_LENGTH && folded.contains(part)) {
                    out.add("Don't use your name or email in the password");
                    return out;
                }
            }
        }
        return out;
    }

    private static String fold(String s) {
        String decomposed = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return decomposed.toLowerCase(Locale.ROOT);
    }
}
