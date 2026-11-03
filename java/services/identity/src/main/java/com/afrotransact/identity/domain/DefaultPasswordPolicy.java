package com.afrotransact.identity.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;

/** 12+ characters (the shortest length is a runtime setting, S291), upper case, lower case, a digit, and none of the user's names or email local part. */
public class DefaultPasswordPolicy implements PasswordPolicy {

    /** Personal values shorter than this are too common to block (for example "Al" or "Bo"). */
    private static final int MIN_PERSONAL_LENGTH = 3;

    private final IntSupplier minLength;

    public DefaultPasswordPolicy() {
        this(() -> MIN_LENGTH);
    }

    /** @param minLength read on every check, so a changed setting applies to the next password */
    public DefaultPasswordPolicy(IntSupplier minLength) {
        this.minLength = minLength;
    }

    @Override
    public List<String> violations(String password, List<String> personal) {
        List<String> out = new ArrayList<>();
        String pw = password == null ? "" : password;
        int min = minLength.getAsInt();
        if (pw.length() < min) {
            out.add("Use at least " + min + " characters");
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
