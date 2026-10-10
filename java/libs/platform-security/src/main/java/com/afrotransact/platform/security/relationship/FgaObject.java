package com.afrotransact.platform.security.relationship;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An object of the authorisation model ({@code contracts/openfga/model.fga}), for example {@code offer:<uuid>} or
 * {@code organization:org_123}.
 *
 * @param type model type, lower snake case ({@code offer}, {@code organization}, {@code ads_campaign})
 * @param id   the owning service's id; no whitespace, {@code #} or {@code :}
 */
public record FgaObject(String type, String id) {

    private static final Pattern TYPE = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern ID = Pattern.compile("[^\\s#:]+");

    /** Organisation objects: {@code organization:<seller org id>} (the JWT {@code org} claim). */
    public static final String ORGANIZATION = "organization";
    /** The single platform object that holds platform-wide staff roles and first-party service clients. */
    public static final FgaObject PLATFORM = new FgaObject("platform", "afrotransact");

    public FgaObject {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        if (!TYPE.matcher(type).matches()) {
            throw new IllegalArgumentException("Invalid FGA type: " + type);
        }
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid FGA object id: " + id);
        }
    }

    public static FgaObject of(String type, Object id) {
        return new FgaObject(type, String.valueOf(id));
    }

    public static FgaObject organization(String orgId) {
        return new FgaObject(ORGANIZATION, orgId);
    }

    /** The {@code type:id} form OpenFGA uses. */
    public String key() {
        return type + ":" + id;
    }

    @Override
    public String toString() {
        return key();
    }
}
