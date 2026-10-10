package com.afrotransact.mcp.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Field-name rules plus value patterns (emails and phone numbers inside free text). */
public class DefaultPiiMasker implements PiiMasker {

    private static final Pattern LAST_FOUR_KEYS = Pattern.compile(
            "(?i).*(taxid|tax_id|ssn|ein|tin|itin|accountnumber|account_number|iban|routing|cardnumber|passport"
                    + "|licensenumber|idnumber|nationalid).*");
    private static final Pattern DOB_KEYS = Pattern.compile("(?i).*(dateofbirth|date_of_birth|dob|birthdate).*");
    private static final Pattern ADDRESS_KEYS = Pattern.compile("(?i).*(street|line1|line2|address1|address2).*");
    private static final Pattern EMAIL = Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})");
    private static final Pattern PHONE = Pattern.compile("(?<!\\w)(\\+?\\d[\\d\\s().-]{6,}\\d)(?!\\w)");

    @Override
    public Object mask(Object tree) {
        return mask(null, tree);
    }

    private Object mask(String key, Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), mask(String.valueOf(k), v)));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(mask(key, v)));
            return out;
        }
        if (value instanceof String s) {
            return maskString(key == null ? "" : key.toLowerCase(Locale.ROOT), s);
        }
        if (value instanceof Number n && key != null && LAST_FOUR_KEYS.matcher(key).matches()) {
            return lastFour(n.toString());
        }
        return value;
    }

    private static String maskString(String key, String s) {
        if (LAST_FOUR_KEYS.matcher(key).matches()) {
            return lastFour(s);
        }
        if (DOB_KEYS.matcher(key).matches()) {
            return s.length() >= 4 ? s.substring(0, 4) + "-**-**" : "****";
        }
        if (ADDRESS_KEYS.matcher(key).matches()) {
            return s.isBlank() ? s : "*** (masked street address)";
        }
        String out = EMAIL.matcher(s).replaceAll("$1***$2");
        Matcher m = PHONE.matcher(out);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String digits = m.group(1).replaceAll("\\D", "");
            m.appendReplacement(b, digits.length() >= 10 && digits.length() <= 15
                    ? Matcher.quoteReplacement("***-***-" + digits.substring(digits.length() - 4))
                    : Matcher.quoteReplacement(m.group(1)));
        }
        m.appendTail(b);
        return b.toString();
    }

    private static String lastFour(String s) {
        String digits = s.replaceAll("[^0-9A-Za-z]", "");
        return digits.length() <= 4 ? "****" : "****" + digits.substring(digits.length() - 4);
    }
}
