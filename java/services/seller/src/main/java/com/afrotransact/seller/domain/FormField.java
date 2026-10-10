package com.afrotransact.seller.domain;

import java.util.List;

/**
 * One typed input of a requirement form.
 *
 * @param type    TEXT, DATE, CHECKBOX, SELECT, URL, ASSET (an uploaded media asset id) or TOKEN (Stripe.js token)
 * @param pattern optional regular expression the value must match
 */
public record FormField(String name, String label, String type, boolean required, List<String> options,
                        String pattern) {

    public FormField {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
