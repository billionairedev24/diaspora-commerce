package com.afrotransact.content.domain.site;

/**
 * Company address shown in legal pages and the footer.
 *
 * @param name       legal entity
 * @param line1      street
 * @param line2      suite (optional)
 * @param city       city
 * @param region     state / province
 * @param postalCode ZIP
 * @param country    ISO country
 */
public record PostalAddress(String name, String line1, String line2, String city, String region, String postalCode,
                            String country) {

    /** "AfroTransact, Inc. · 1001 Texas Ave, Suite 1400 · Houston, TX 77002". */
    public String singleLine() {
        String street = line2 == null || line2.isBlank() ? line1 : line1 + ", " + line2;
        return name + " · " + street + " · " + city + ", " + region + " " + postalCode;
    }
}
