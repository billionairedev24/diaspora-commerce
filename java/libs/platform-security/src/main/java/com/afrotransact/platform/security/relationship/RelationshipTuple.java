package com.afrotransact.platform.security.relationship;

import java.util.Objects;

/**
 * One relationship tuple: {@code user} has {@code relation} on {@code object}.
 *
 * @param user     {@code user:<id>}, {@code client:<id>}, {@code user:*}, an object ({@code organization:org_1}) or a
 *                 userset ({@code organization:org_1#member})
 * @param relation relation name from the model
 * @param object   {@code type:id}
 */
public record RelationshipTuple(String user, String relation, String object) {

    public RelationshipTuple {
        requireText(user, "user");
        requireText(relation, "relation");
        requireText(object, "object");
    }

    public static RelationshipTuple of(String user, String relation, FgaObject object) {
        return new RelationshipTuple(user, relation, object.key());
    }

    /** {@code subject} (an object such as an organisation) has {@code relation} on {@code object}. */
    public static RelationshipTuple of(FgaObject subject, String relation, FgaObject object) {
        return new RelationshipTuple(subject.key(), relation, object.key());
    }

    /** The resource's owning organisation: {@code organization:<orgId>} is {@code org} of {@code resource}. */
    public static RelationshipTuple ownedBy(FgaObject resource, String orgId) {
        return of(FgaObject.organization(orgId), "org", resource);
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
