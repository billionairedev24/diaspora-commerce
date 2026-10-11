package com.afrotransact.identity.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Default sign-up validation composed from the password and phone policies. The mobile number is optional (it can be
 * added later from the account's security settings); when given it must be a valid mobile number.
 */
public class DefaultSignUpPolicy implements SignUpPolicy {

    private static final Pattern NAME = Pattern.compile("^\\p{L}[\\p{L}\\p{M} '’\\-]{0,79}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$");

    private final PasswordPolicy passwords;
    private final PhoneNumberPolicy phones;

    public DefaultSignUpPolicy(PasswordPolicy passwords, PhoneNumberPolicy phones) {
        this.passwords = passwords;
        this.phones = phones;
    }

    @Override
    public ValidatedSignUp validate(SignUpDetails d) {
        Map<String, String> errors = new LinkedHashMap<>();
        String first = trim(d.firstName());
        String last = trim(d.lastName());
        if (!validName(first)) {
            errors.put("firstName", "Use letters, spaces, hyphens or apostrophes");
        }
        if (!validName(last)) {
            errors.put("lastName", "Use letters, spaces, hyphens or apostrophes");
        }
        String email = UserAccount.normaliseEmail(d.email());
        if (email == null || email.length() > 254 || !EMAIL.matcher(email).matches()) {
            errors.put("email", "Enter a valid email address");
        }
        String phoneE164 = null;
        if (d.phone() != null && !d.phone().isBlank()) {
            PhoneCheck phone = phones.check(d.phone(), d.phoneRegion());
            if (phone.valid()) {
                phoneE164 = phone.e164();
            } else {
                errors.put("phone", phone.error());
            }
        }
        String local = email == null ? "" : email.substring(0, Math.max(0, email.indexOf('@')));
        List<String> pw = passwords.violations(d.password(), List.of(nullToEmpty(first), nullToEmpty(last), local));
        if (!pw.isEmpty()) {
            errors.put("password", String.join("; ", pw));
        }
        if (d.password() == null || !d.password().equals(d.confirmPassword())) {
            errors.put("confirmPassword", "Passwords don't match");
        }
        if (!d.termsAccepted()) {
            errors.put("termsAccepted", "Accept the Conditions of Use and Privacy Notice to continue");
        }
        if (!errors.isEmpty()) {
            throw new IdentityRuleViolation("identity.invalid-sign-up", "Check the highlighted fields", errors);
        }
        return new ValidatedSignUp(first, last, email, phoneE164);
    }

    @Override
    public boolean validName(String name) {
        return name != null && NAME.matcher(name).matches() && !name.contains("  ");
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
