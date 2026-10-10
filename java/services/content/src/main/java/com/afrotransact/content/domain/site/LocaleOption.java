package com.afrotransact.content.domain.site;

/**
 * A language offered in the language picker.
 *
 * @param code BCP 47 language (en, fr, es, ar)
 * @param name endonym
 * @param rtl  right-to-left script
 */
public record LocaleOption(String code, String name, boolean rtl) {
}
